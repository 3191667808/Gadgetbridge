package nodomain.freeyourgadget.gadgetbridge.activities

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.Intent.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.core.content.edit
import androidx.core.net.toUri
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst
import nodomain.freeyourgadget.gadgetbridge.databinding.ActivityAuthKeyBinding
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate
import nodomain.freeyourgadget.gadgetbridge.util.DeviceHelper
import nodomain.freeyourgadget.gadgetbridge.util.GB
import org.slf4j.Logger
import org.slf4j.LoggerFactory


class AuthKeyActivity : AbstractGBActivity() {
    private lateinit var binding: ActivityAuthKeyBinding
    private var deviceCandidate: GBDeviceCandidate? = null
    private var coordinator: DeviceCoordinator? = null
    private lateinit var authHelperLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthKeyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        @Suppress("DEPRECATION")
        deviceCandidate = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DEVICE_CANDIDATE, GBDeviceCandidate::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_DEVICE_CANDIDATE)
        }

        deviceCandidate?.let { candidate ->
            coordinator = DeviceHelper.getInstance().resolveDeviceType(candidate).deviceCoordinator
        }

        if (deviceCandidate == null || coordinator == null) {
            Toast.makeText(this, "Device info missing", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        title = getString(
            R.string.auth_key_activity_title_for,
            deviceCandidate?.name ?: getString(R.string.devicetype_unknown)
        )

        deviceCandidate?.macAddress?.let { macAddress ->
            val sharedPrefs = GBApplication.getDeviceSpecificSharedPrefs(macAddress)
            val authKey = sharedPrefs.getString(DeviceSettingsPreferenceConst.PREF_AUTH_KEY, "")
            if (authKey?.isNotEmpty() == true) {
                binding.authKeyEditText.setText(authKey)
            }
        }

        binding.submitAuthKeyButton.setOnClickListener {
            val authKey = binding.authKeyEditText.text.toString().trim()

            if (authKey.isEmpty()) {
                binding.authKeyInputLayout.error = getString(R.string.auth_key_required_message)
                return@setOnClickListener
            } else {
                // Clear error
                binding.authKeyInputLayout.error = null
            }

            if (coordinator?.validateAuthKey(authKey) == true) {
                // Save the auth key
                deviceCandidate?.macAddress?.let { macAddress ->
                    val sharedPrefs = GBApplication.getDeviceSpecificSharedPrefs(macAddress)
                    sharedPrefs.edit { putString(DeviceSettingsPreferenceConst.PREF_AUTH_KEY, authKey) }
                }

                // Return the auth key and candidate to the calling activity
                val resultIntent = Intent().apply {
                    putExtra(EXTRA_AUTH_KEY_RESULT, authKey)
                    putExtra(EXTRA_DEVICE_CANDIDATE_RESULT, deviceCandidate)
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            } else {
                binding.authKeyInputLayout.error = getString(R.string.invalid_auth_key_message)
            }
        }

        authHelperLauncher = registerForActivityResult(
            StartActivityForResult(),
            this::handleAuthHelperResult
        )

        binding.authHelperButton.setOnClickListener {
            launchAuthHelper()
        }
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_auth_key, menu)
        val helpItem = menu.findItem(R.id.auth_key_help)
        helpItem?.isVisible = coordinator?.authHelp != null
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.auth_key_help -> {
                coordinator?.authHelp?.let { url ->
                    val intent = Intent(ACTION_VIEW, coordinator?.authHelp!!.toUri())
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        GB.toast(this, getString(R.string.cannot_open_help_url), Toast.LENGTH_SHORT, GB.ERROR, e)
                    }
                } ?: GB.toast(this, "No help URL available.", Toast.LENGTH_SHORT, GB.ERROR)
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun launchAuthHelper() {
        val brand = coordinator?.getAuthHelperBrand()

        if (brand == null) {
            LOG.warn("Device does not support Auth Helper")
            return
        }

        // Check if Auth Helper app is installed
        if (!isAuthHelperInstalled()) {
            try {
                startActivity(
                    Intent(
                        ACTION_VIEW,
                        "market://details?id=nodomain.freeyourgadget.authhelper".toUri()
                    )
                )
            } catch (e: ActivityNotFoundException) {
                GB.toast(
                    this,
                    getString(R.string.install_app_fail, "Auth Helper"),
                    Toast.LENGTH_LONG,
                    GB.WARN,
                    e
                )
            }
            return
        }

        val authIntent = Intent("nodomain.freeyourgadget.authhelper.action.AUTHENTICATE")
        authIntent.setPackage("nodomain.freeyourgadget.authhelper")
        authIntent.putExtra("nodomain.freeyourgadget.authhelper.EXTRA_BRAND", brand)
        authIntent.putExtra("nodomain.freeyourgadget.authhelper.EXTRA_MAC", deviceCandidate?.macAddress)

        authHelperLauncher.launch(authIntent)
    }

    private fun isAuthHelperInstalled(): Boolean {
        try {
            packageManager.getApplicationInfo("nodomain.freeyourgadget.authhelper", 0)
            return true
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
    }

    private fun handleAuthHelperResult(result: ActivityResult) {
        if (result.resultCode != RESULT_OK) {
            LOG.debug("Auth Helper returned non-OK result code: {}", result.resultCode)
            GB.toast(
                this,
                getString(R.string.auth_helper_failed),
                Toast.LENGTH_LONG,
                GB.WARN
            )
            return
        }

        val data: Intent? = result.data
        if (data == null) {
            LOG.warn("Auth Helper returned null data")
            GB.toast(
                this,
                getString(R.string.auth_helper_failed),
                Toast.LENGTH_LONG,
                GB.WARN
            )
            return
        }

        val authKey = data.getStringExtra("nodomain.freeyourgadget.authhelper.RESULT_KEY")
        if (authKey == null) {
            LOG.warn("Auth Helper did not return an auth key")
            GB.toast(
                this,
                getString(R.string.auth_helper_failed),
                Toast.LENGTH_LONG,
                GB.WARN
            )
            return
        }

        LOG.debug("Received auth key from Auth Helper")

        deviceCandidate?.macAddress?.let { macAddress ->
            val sharedPrefs = GBApplication.getDeviceSpecificSharedPrefs(macAddress)
            sharedPrefs.edit { putString(DeviceSettingsPreferenceConst.PREF_AUTH_KEY, authKey) }
        }

        // Return the auth key and candidate to the calling activity
        val resultIntent = Intent().apply {
            putExtra(EXTRA_AUTH_KEY_RESULT, authKey)
            putExtra(EXTRA_DEVICE_CANDIDATE_RESULT, deviceCandidate)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(AuthKeyActivity::class.java)

        const val EXTRA_DEVICE_CANDIDATE = "EXTRA_DEVICE_CANDIDATE_FOR_AUTH"
        const val EXTRA_AUTH_KEY_RESULT = "EXTRA_AUTH_KEY_RESULT"
        const val EXTRA_DEVICE_CANDIDATE_RESULT = "EXTRA_DEVICE_CANDIDATE_RESULT"

        fun newIntent(context: Context, deviceCandidate: GBDeviceCandidate): Intent {
            return Intent(context, AuthKeyActivity::class.java).apply {
                putExtra(EXTRA_DEVICE_CANDIDATE, deviceCandidate)
            }
        }
    }
}
