package nodomain.freeyourgadget.gadgetbridge.activities.endurain

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.edit
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.util.GB
import org.slf4j.LoggerFactory

class DreeveSetupBottomSheet : BottomSheetDialogFragment() {
    private val LOG = LoggerFactory.getLogger(DreeveSetupBottomSheet::class.java)
    private val prefs get() = GBApplication.getPrefs().preferences

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(
        R.layout.dreeve_bottomsheet_setup_wizard,
        container,
        false
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val serverNameInput = view.findViewById<EditText>(R.id.dreeve_server_name)
        val apiTokenInput = view.findViewById<EditText>(R.id.dreeve_api_token)
        val saveButton = view.findViewById<Button>(R.id.save_button)

        serverNameInput.setText(prefs.getString("dreeve_server", ""))

        saveButton.setOnClickListener {
            val serverName = serverNameInput.text.toString().trim()
            val apiToken = apiTokenInput.text.toString().trim()

            if (serverName.isEmpty() || apiToken.isEmpty()) {
                GB.toast("Please fill both fields", Toast.LENGTH_SHORT, GB.WARN)
                return@setOnClickListener
            }
            if (!apiToken.startsWith("drv_")) {
                GB.toast("API token should start with drv_", Toast.LENGTH_SHORT, GB.WARN)
                return@setOnClickListener
            }

            saveButton.isEnabled = false
            DreeveApiClient(serverName, DreeveTokenManager(requireContext())).checkServerReachable(apiToken) { reachable, reason ->
                activity?.runOnUiThread {
                    saveButton.isEnabled = true
                    if (!reachable) {
                        GB.toast(
                            reason ?: "Unknown error",
                            Toast.LENGTH_LONG,
                            GB.ERROR
                        )
                        return@runOnUiThread
                    }
                    LOG.info("Saving Dreeve server ({}) and API token", serverName)
                    prefs.edit { putString("dreeve_server", serverName) }
                    DreeveTokenManager(requireContext()).saveToken(apiToken)
                    parentFragmentManager.setFragmentResult(
                        "dreeve_login_result",
                        Bundle().apply { putBoolean("success", true) }
                    )
                    dismiss()
                }
            }
        }
    }
}