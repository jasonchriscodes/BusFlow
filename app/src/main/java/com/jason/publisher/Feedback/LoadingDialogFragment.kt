package com.jason.publisher

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager

class LoadingDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.feedback_loading)
        dialog.setCancelable(false)
        return dialog
    }

    /**
     * Show the loading dialog.
     */
    fun showLoading(fragmentManager: FragmentManager) {
        show(fragmentManager, "loading")
    }

    /**
     * Hide the loading dialog.
     */
    fun hideLoading() {
        dismiss()
    }
}
