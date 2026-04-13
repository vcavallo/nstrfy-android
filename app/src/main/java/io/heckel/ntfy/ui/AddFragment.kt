package io.heckel.ntfy.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.msg.NostrConstants
import io.heckel.ntfy.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AddFragment : DialogFragment() {
    private lateinit var repository: Repository
    private lateinit var subscribeListener: SubscribeListener

    private lateinit var toolbar: MaterialToolbar
    private lateinit var actionMenuItem: MenuItem
    private lateinit var subscribeTopicText: TextInputEditText
    private lateinit var whitelistCheckbox: CheckBox

    interface SubscribeListener {
        fun onSubscribe(topic: String, baseUrl: String, instant: Boolean, whitelistEnabled: Boolean)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        subscribeListener = activity as SubscribeListener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        if (activity == null) {
            throw IllegalStateException("Activity cannot be null")
        }

        repository = Repository.getInstance(requireActivity())

        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_add_dialog, null)

        // Setup toolbar
        toolbar = view.findViewById(R.id.add_dialog_toolbar)
        toolbar.setNavigationOnClickListener { dismiss() }
        toolbar.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.add_dialog_action_button) {
                onSubscribeClick()
                true
            } else {
                false
            }
        }
        actionMenuItem = toolbar.menu.findItem(R.id.add_dialog_action_button)

        // Show subscribe view, hide login view
        val subscribeView = view.findViewById<View>(R.id.add_dialog_subscribe_view)
        subscribeView.visibility = View.VISIBLE
        val loginView = view.findViewById<View>(R.id.add_dialog_login_view)
        loginView.visibility = View.GONE

        // Topic name field
        subscribeTopicText = view.findViewById(R.id.add_dialog_subscribe_topic_text)

        // Hide server URL fields — not applicable for nostr
        view.findViewById<View>(R.id.add_dialog_subscribe_base_url_layout)?.visibility = View.GONE
        view.findViewById<View>(R.id.add_dialog_subscribe_use_another_server_checkbox)?.visibility = View.GONE
        view.findViewById<View>(R.id.add_dialog_subscribe_use_another_server_description)?.visibility = View.GONE
        view.findViewById<View>(R.id.add_dialog_subscribe_instant_delivery_description)?.visibility = View.GONE
        view.findViewById<View>(R.id.add_dialog_subscribe_foreground_description)?.visibility = View.GONE
        view.findViewById<View>(R.id.add_dialog_subscribe_error_text)?.visibility = View.GONE
        view.findViewById<View>(R.id.add_dialog_subscribe_error_text_image)?.visibility = View.GONE

        // Repurpose the instant delivery checkbox as "enable whitelist"
        val whitelistBox = view.findViewById<View>(R.id.add_dialog_subscribe_instant_delivery_box)
        whitelistBox?.visibility = View.VISIBLE
        whitelistCheckbox = view.findViewById(R.id.add_dialog_subscribe_instant_delivery_checkbox)
        whitelistCheckbox.text = getString(R.string.add_dialog_whitelist_checkbox)
        whitelistCheckbox.isChecked = true
        // Hide the bolt icon next to the checkbox
        view.findViewById<View>(R.id.add_dialog_subscribe_instant_image)?.visibility = View.GONE

        // Validate on text change
        subscribeTopicText.addTextChangedListener(AfterChangedTextWatcher {
            validateInput()
        })

        val dialog = Dialog(requireContext(), R.style.Theme_App_FullScreenDialog)
        dialog.setContentView(view)

        validateInput()
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    override fun onResume() {
        super.onResume()
        subscribeTopicText.postDelayed({
            subscribeTopicText.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(subscribeTopicText, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun onSubscribeClick() {
        val topic = subscribeTopicText.text.toString()
        val whitelistEnabled = whitelistCheckbox.isChecked
        subscribeListener.onSubscribe(topic, NostrConstants.NOSTR_BASE_URL, true, whitelistEnabled)
        dialog?.dismiss()
    }

    private fun validateInput() {
        if (!this::actionMenuItem.isInitialized) return
        val topic = subscribeTopicText.text.toString()

        lifecycleScope.launch(Dispatchers.IO) {
            val existing = repository.getSubscription(NostrConstants.NOSTR_BASE_URL, topic)
            activity?.runOnUiThread {
                actionMenuItem.isEnabled = validTopic(topic) && existing == null
                        && !DISALLOWED_TOPICS.contains(topic)
            }
        }
    }

    companion object {
        const val TAG = "NtfyAddFragment"
        private val DISALLOWED_TOPICS = listOf("docs", "static", "file")
    }
}
