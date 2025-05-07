package com.jason.publisher.main.services

import android.content.Context
import androidx.appcompat.app.AlertDialog

/**
 * Class responsible for displaying a mode selection dialog.
 *
 * @param context The application context.
 */
class ModeSelectionDialog(private val context: Context) {

    /**
     * Interface for handling mode selection events.
     */
    interface ModeSelectionListener {

        /**
         * Called when a mode is selected.
         *
         * @param mode The selected mode.
         */
        fun onModeSelected(mode: String)
    }

}
