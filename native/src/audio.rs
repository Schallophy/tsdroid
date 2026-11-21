//! Voice: Opus in both directions, with the jitter/PLC handling tsclientlib
//! already provides.
//!
//! Capture and playback stay on the Android side (AudioRecord/AudioTrack, echo
//! cancellation, AEC, the Bluetooth SCO route). This module only converts:
//!
//! ```text
//! mic PCM16 ──► encode ──► OutAudio ──► server
//! server ──► AudioHandler ──► mixed PCM16 ──► playback queue
//! ```
//!
//! Mixing happens inside `AudioHandler::fill_buffer`, which also applies each
//! talker's volume and drops clients that stopped sending.

use std::sync::Arc;

use audiopus::coder::Encoder;
use audiopus::{
    Application, Bitrate, Channels, SampleRate,
};
use tsclientlib::audio::AudioHandler;
use tsclientlib::{ClientId, Connection};
use tsproto_packets::packets::{AudioData, CodecType, InAudioBuf, OutAudio};

use crate::handle::{Inner, FRAME_SAMPLES};

/// One 20 ms frame as `tsclientlib` hands it back: **interleaved stereo**.
///
/// `AudioHandler` decodes with `Channels::Stereo`, so a frame is 960 samples per
/// channel — 1920 `f32` in total. Filling a mono-sized buffer instead would take
/// only half a frame per tick, which plays at half speed and sounds like someone
/// dragged the tape: exactly the "low pitch, slowed down" symptom.
const STEREO_FRAME_SAMPLES: usize = FRAME_SAMPLES * 2;

pub struct AudioEngine {
    encoder: Option<Encoder>,
    handler: AudioHandler<ClientId>,
    /// Opus packet counter. Wraps on purpose: it is a `u16` on the wire.
    sequence: u16,
    enabled: bool,
    mix: Vec<f32>,
}

impl AudioEngine {
    pub fn new() -> Self {
        let encoder = match Encoder::new(SampleRate::Hz48000, Channels::Mono, Application::Voip) {
            Ok(mut encoder) => {
                // 24 kbps mono is what the desktop client uses for voice; going
                // higher buys nothing on a channel codec that re-encodes anyway.
                if let Err(e) = encoder.set_bitrate(Bitrate::BitsPerSecond(24_000)) {
                    log::warn!("Could not set the Opus bitrate: {e}");
                }
                Some(encoder)
            }
            Err(e) => {
                log::error!("No Opus encoder available; voice will not be sent: {e}");
                None
            }
        };

        AudioEngine {
            encoder,
            handler: AudioHandler::new(),
            sequence: 0,
            enabled: true,
            mix: vec![0.0; STEREO_FRAME_SAMPLES],
        }
    }

    pub fn set_enabled(&mut self, enabled: bool) {
        self.enabled = enabled;
        log::info!("Voice transmit {}", if enabled { "enabled" } else { "muted" });
    }

    /// Encodes one 20 ms frame and sends it. Silent frames are skipped: they cost
    /// bandwidth and would keep the speaking indicator lit.
    pub fn transmit(&mut self, connection: &mut Connection, frame: &[i16]) {
        if !self.enabled {
            return;
        }
        let peak = frame.iter().map(|s| s.unsigned_abs()).max().unwrap_or(0);
        if peak < 120 {
            return;
        }
        let Some(encoder) = self.encoder.as_mut() else {
            return;
        };

        let mut encoded = [0u8; 1500];
        match encoder.encode(frame, &mut encoded) {
            Ok(len) => {
                let packet = OutAudio::new(&AudioData::C2S {
                    id: self.sequence,
                    codec: CodecType::OpusVoice,
                    data: &encoded[..len],
                });
                if let Err(e) = connection.send_audio(packet) {
                    log::warn!("Sending audio failed: {e}");
                }
                self.sequence = self.sequence.wrapping_add(1);
            }
            Err(e) => log::warn!("Opus encode failed: {e}"),
        }
    }

    /// Feeds one received packet into the jitter buffer and updates who is
    /// talking, so the UI can draw a speaking ring without a second timer.
    pub fn receive(&mut self, inner: &Arc<Inner>, packet: InAudioBuf) {
        // The sender is inside the packet, not next to it.
        let client = match packet.data().data() {
            AudioData::S2C { from, .. } => ClientId(*from),
            AudioData::S2CWhisper { from, .. } => ClientId(*from),
            _ => return,
        };
        if let Err(e) = self.handler.handle_packet(client, packet) {
            // Unsupported codec (CELT/Music modes) is normal on some servers.
            log::debug!("Ignoring audio packet from {}: {e}", client.0);
            return;
        }
        self.publish_talkers(inner);
    }

    /// Produces one playback frame per tick. Runs on a clock rather than on
    /// packet arrival so playback stays smooth when packets bunch up.
    pub fn tick(&mut self, inner: &Arc<Inner>) {
        // `fill_buffer` fills up to `buf.len()` and does not clear it first.
        self.mix.iter_mut().for_each(|sample| *sample = 0.0);
        self.handler.fill_buffer(&mut self.mix);

        // Downmix L/R to the mono stream the phone plays. TS voice is mono to
        // begin with; the stereo split only exists inside the handler's decoder.
        let mut pcm = [0i16; FRAME_SAMPLES];
        for (index, slot) in pcm.iter_mut().enumerate() {
            let left = self.mix[index * 2];
            let right = self.mix[index * 2 + 1];
            let mono = (left + right) * 0.5;
            *slot = (mono.clamp(-1.0, 1.0) * i16::MAX as f32) as i16;
        }
        inner.push_playback(&pcm);

        self.publish_talkers(inner);
    }

    fn publish_talkers(&self, inner: &Arc<Inner>) {
        let mut ids: Vec<i32> = self
            .handler
            .get_queues()
            .keys()
            .map(|id| id.0 as i32)
            .collect();
        ids.sort_unstable();
        inner.set_talking(ids);
    }
}
