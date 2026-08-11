/*  Copyright (C) 2026 Mark Struchkov

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.activities.endurain

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.util.GB
import nodomain.freeyourgadget.gadgetbridge.util.InternetUtils
import org.slf4j.LoggerFactory

class RideHubSetupBottomSheet : BottomSheetDialogFragment() {
    private val LOG = LoggerFactory.getLogger(RideHubSetupBottomSheet::class.java)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(
        R.layout.ridehub_bottomsheet_setup_wizard,
        container,
        false
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val apiTokenInput = view.findViewById<EditText>(R.id.ridehub_api_token)
        val saveButton = view.findViewById<Button>(R.id.save_button)

        saveButton.setOnClickListener {
            val apiToken = apiTokenInput.text.toString().trim()

            if (apiToken.isEmpty()) {
                GB.toast(getString(R.string.ridehub_setup_missing_token), Toast.LENGTH_SHORT, GB.WARN)
                return@setOnClickListener
            }
            if (!apiToken.startsWith(RideHubApiClient.TOKEN_PREFIX)) {
                GB.toast(getString(R.string.ridehub_setup_api_token_error), Toast.LENGTH_SHORT, GB.WARN)
                return@setOnClickListener
            }

            // Verify the token is accepted before saving, so a mistyped or revoked
            // token is reported now instead of silently at the first upload.
            saveButton.isEnabled = false
            RideHubApiClient(RideHubTokenManager(requireContext())).checkServerReachable(apiToken) { reachable, reason ->
                activity?.runOnUiThread {
                    saveButton.isEnabled = true
                    if (!reachable) {
                        GB.toast(
                            reason ?: InternetUtils.connectFailureReason(
                                requireContext(),
                                RideHubApiClient.BASE_URL
                            ),
                            Toast.LENGTH_LONG,
                            GB.ERROR
                        )
                        return@runOnUiThread
                    }
                    LOG.info("Saving RideHub API token")
                    RideHubTokenManager(requireContext()).saveToken(apiToken)
                    parentFragmentManager.setFragmentResult(
                        "ridehub_login_result",
                        Bundle().apply { putBoolean("success", true) }
                    )
                    dismiss()
                }
            }
        }
    }
}
