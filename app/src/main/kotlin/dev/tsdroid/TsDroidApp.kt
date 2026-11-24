package dev.tsdroid

import android.app.Application

/**
 * The application entry point.
 *
 * Nothing to install here: the native bridge, the audio route and the account
 * client are created by the ViewModel, and the UI is composed by the activity.
 */
class TsDroidApp : Application()
