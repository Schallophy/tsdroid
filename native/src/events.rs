//! Translation from tsclientlib events to the flat, string-typed events the
//! Kotlin side matches on.
//!
//! The Kotlin contract is `Event(type: String, data: Map<String, Any>)` — see
//! `ServerViewModel.handleEvent`, whose `when (event.type)` lists every name
//! this module may produce. Event names and field keys are therefore API, not
//! implementation detail: renaming one silently breaks a feature.

use jni::JNIEnv;
use jni::objects::JObject;
use jni::sys::jobjectArray;

/// A value carried by an event. Deliberately small: the Kotlin side only reads
/// strings, ints and longs out of the map.
#[derive(Debug, Clone)]
pub enum Value {
    Str(String),
    Int(i32),
    Long(i64),
    Bool(bool),
}

#[derive(Debug, Clone)]
pub struct QueuedEvent {
    pub kind: String,
    pub data: Vec<(String, Value)>,
}

impl QueuedEvent {
    pub fn new(kind: impl Into<String>) -> Self {
        QueuedEvent { kind: kind.into(), data: Vec::new() }
    }

    pub fn str(mut self, key: &str, value: impl Into<String>) -> Self {
        self.data.push((key.to_string(), Value::Str(value.into())));
        self
    }

    pub fn int(mut self, key: &str, value: i32) -> Self {
        self.data.push((key.to_string(), Value::Int(value)));
        self
    }

    pub fn long(mut self, key: &str, value: i64) -> Self {
        self.data.push((key.to_string(), Value::Long(value)));
        self
    }

    pub fn bool(mut self, key: &str, value: bool) -> Self {
        self.data.push((key.to_string(), Value::Bool(value)));
        self
    }
}

/// Builds the `java.util.HashMap[]` the caller returns to Java.
///
/// Deliberately plain JDK types: no app-defined class is referenced here, so the
/// native library does not have to know the UI's package or be rebuilt when the
/// UI changes. The first key of every map is `"type"`, which is what the Kotlin
/// side switches on.
pub fn to_java_array(
    env: &mut JNIEnv,
    events: Vec<QueuedEvent>,
) -> jni::errors::Result<jobjectArray> {
    let map_class = env.find_class("java/util/HashMap")?;
    let array = env.new_object_array(
        events.len() as i32,
        &map_class,
        &JObject::null(),
    )?;

    for (index, event) in events.into_iter().enumerate() {
        let map = env.new_object(&map_class, "()V", &[])?;
        put(env, &map, "type", Value::Str(event.kind))?;
        for (key, value) in event.data {
            put(env, &map, &key, value)?;
        }
        env.set_object_array_element(&array, index as i32, &map)?;
    }

    Ok(array.into_raw())
}

fn put(env: &mut JNIEnv, map: &JObject, key: &str, value: Value) -> jni::errors::Result<()> {
    use jni::objects::JValue;

    let key = env.new_string(key)?;
    let value: JObject = match value {
        Value::Str(text) => env.new_string(text)?.into(),
        Value::Int(number) => env
            .new_object("java/lang/Integer", "(I)V", &[JValue::Int(number)])?
            .into(),
        Value::Long(number) => env
            .new_object("java/lang/Long", "(J)V", &[JValue::Long(number)])?
            .into(),
        Value::Bool(flag) => env
            .new_object("java/lang/Boolean", "(Z)V", &[JValue::Bool(flag as u8)])?
            .into(),
    };
    env.call_method(
        map,
        "put",
        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
        &[(&key).into(), (&value).into()],
    )?;
    Ok(())
}
