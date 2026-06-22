/*  Copyright (C) 2026

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.activities.fitquest

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.edit
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.util.GB
import org.slf4j.LoggerFactory

/**
 * Bottom-sheet setup wizard for FitQuest.
 *
 * Mirrors the Wanderer / Endurain wizards: server URL + credentials,
 * a Save button that triggers login on a background thread, and a
 * result bundle so the parent preferences screen can refresh its
 * "Logged in as X" status row.
 *
 * Unlike Wanderer (single API token field) or Endurain (server +
 * local-or-SSO + optional MFA), FitQuest is the simplest of the
 * three — username + password — so the layout is correspondingly
 * minimal.
 */
class FitQuestSetupBottomSheet : BottomSheetDialogFragment() {
    private val LOG = LoggerFactory.getLogger(FitQuestSetupBottomSheet::class.java)

    private lateinit var serverInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var saveButton: Button
    private lateinit var progressBar: ProgressBar

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(
        R.layout.fitquest_bottomsheet_setup_wizard,
        container,
        false
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        serverInput = view.findViewById(R.id.fitquest_server_name)
        usernameInput = view.findViewById(R.id.fitquest_username)
        passwordInput = view.findViewById(R.id.fitquest_password)
        saveButton = view.findViewById(R.id.fitquest_save_button)
        progressBar = view.findViewById(R.id.fitquest_login_progress)

        // Pre-fill with whatever the user already saved. Password
        // is never persisted, so it stays empty.
        val prefs = requireContext().getSharedPreferences(
            FitQuestTokenManager.PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        )
        serverInput.setText(prefs.getString(FitQuestTokenManager.KEY_SERVER, ""))
        usernameInput.setText(prefs.getString(FitQuestTokenManager.KEY_USERNAME, ""))

        saveButton.setOnClickListener {
            val server = serverInput.text.toString().trim()
            val username = usernameInput.text.toString().trim()
            val password = passwordInput.text.toString()

            if (server.isEmpty() || username.isEmpty() || password.isEmpty()) {
                GB.toast(getString(R.string.fitquest_setup_missing_information), Toast.LENGTH_SHORT, GB.WARN)
                return@setOnClickListener
            }
            // Normalize the URL — strip a trailing slash so we
            // don't end up with double slashes when we append
            // /auth/login etc.
            val normalized = server.trimEnd('/')
            attemptLogin(normalized, username, password)
        }
    }

    private fun attemptLogin(server: String, username: String, password: String) {
        // Show a spinner and disable the button while the network
        // request is in flight. OkHttp runs synchronously on the
        // current thread, so we hop to a background thread for
        // the call and come back to the main thread for the UI
        // update. (The token manager's SharedPreferences edits
        // are safe to call from any thread.)
        progressBar.visibility = View.VISIBLE
        saveButton.isEnabled = false

        Thread {
            val apiClient = FitQuestApiClient(server, FitQuestTokenManager(requireContext()))
            val result = apiClient.login(username, password)
            view?.post { handleLoginResult(result) }
        }.start()
    }

    private fun handleLoginResult(result: FitQuestLoginResult) {
        progressBar.visibility = View.GONE
        saveButton.isEnabled = true

        when (result) {
            is FitQuestLoginResult.Success -> {
                LOG.info("FitQuest login successful as {}", result.username)
                parentFragmentManager.setFragmentResult(
                    "fitquest_login_result",
                    Bundle().apply { putBoolean("success", true) }
                )
                dismiss()
            }
            is FitQuestLoginResult.InvalidCredentials -> {
                LOG.warn("FitQuest login: invalid credentials")
                GB.toast(
                    getString(R.string.fitquest_setup_invalid_credentials),
                    Toast.LENGTH_SHORT,
                    GB.WARN
                )
                passwordInput.text?.clear()
                passwordInput.requestFocus()
            }
            is FitQuestLoginResult.NetworkError -> {
                LOG.error("FitQuest login network error: {}", result.message)
                GB.toast(
                    getString(R.string.fitquest_setup_network_error, result.message),
                    Toast.LENGTH_LONG,
                    GB.WARN
                )
            }
        }
    }
}