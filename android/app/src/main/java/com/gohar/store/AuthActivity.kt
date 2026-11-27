package com.gohar.store

import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.gohar.store.databinding.ActivityAuthBinding

/**
 * Owner sign-in, in two modes:
 *
 * SETUP (no owner exists on this phone yet): Talha picks an email +
 * password (8+ chars) and can optionally link one Google account.
 * Saving stores a salted hash — never the password — and signs him in.
 *
 * SIGN IN (setup exists): email + password, or "Continue with Google"
 * with the account that was linked at setup. A reset button erases
 * the whole owner setup on this phone and drops back to SETUP.
 *
 * FORGOT PASSWORD (sign-in mode only): "Forgot password?" opens the
 * device Google account chooser when a Google account was linked at
 * setup. A matching pick proves ownership and switches this screen
 * into a "set new password" state (same password/confirm fields,
 * new title + button) that saves via AuthManager.changePassword()
 * — email and Google link kept, nothing wiped. With no Google
 * account linked, a dialog explains the only way is the reset
 * (erase + set up again) and offers it. There is no emailed reset
 * link — the app has no server.
 *
 * On success this activity finishes with RESULT_OK, and MainActivity
 * refreshes its menu so the developer console appears.
 */
class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private var isSetupMode = true
    private var linkedGoogleEmail: String? = null

    /** True while the owner is setting a new password after proving
     * ownership with the linked Google account (forgot-password
     * Path A). The email field is hidden and the primary button
     * saves the new password instead of signing in. */
    private var isResetPasswordMode = false

    /** Which flow the Google account chooser was opened for: the
     * launcher result means something different for each one. */
    private enum class GoogleChooserPurpose { SETUP_LINK, SIGN_IN, FORGOT_PASSWORD }
    private var googleChooserPurpose = GoogleChooserPurpose.SIGN_IN

    companion object {
        private const val KEY_RESET_PASSWORD_MODE = "reset_password_mode"
    }

    private val googleChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            ToastHelper.show(this, ToastHelper.Type.INFO, R.string.choose_google_account)
            return@registerForActivityResult
        }
        val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME).orEmpty()
        if (email.isBlank()) {
            ToastHelper.show(this, ToastHelper.Type.ERROR, R.string.google_sign_in_failed)
            return@registerForActivityResult
        }
        when (googleChooserPurpose) {
            GoogleChooserPurpose.SETUP_LINK -> {
                linkedGoogleEmail = email
                binding.googleLinkedLabel.text = getString(R.string.google_account_linked, email)
                binding.googleLinkedLabel.visibility = View.VISIBLE
            }
            GoogleChooserPurpose.SIGN_IN -> {
                if (AuthManager.getLinkedGoogleEmail(this) == null) {
                    ToastHelper.show(this, ToastHelper.Type.ERROR, R.string.no_google_linked, long = true)
                    return@registerForActivityResult
                }
                if (AuthManager.signInWithGoogle(this, email)) {
                    ToastHelper.show(
                        this, ToastHelper.Type.SUCCESS,
                        getString(R.string.signed_in_as, email),
                    )
                    setResult(RESULT_OK)
                    finish()
                } else {
                    ToastHelper.show(this, ToastHelper.Type.ERROR, R.string.google_sign_in_failed, long = true)
                }
            }
            GoogleChooserPurpose.FORGOT_PASSWORD -> {
                // Pure match check on purpose: signInWithGoogle()
                // would set the session here, signing the owner in
                // before any new password exists. A match only
                // proves ownership and enters the new-password
                // state; the session is set by changePassword().
                val linked = AuthManager.getLinkedGoogleEmail(this)
                if (linked == null) {
                    ToastHelper.show(this, ToastHelper.Type.ERROR, R.string.no_google_linked, long = true)
                    return@registerForActivityResult
                }
                if (!linked.equals(email.trim(), ignoreCase = true)) {
                    ToastHelper.show(this, ToastHelper.Type.ERROR, R.string.google_sign_in_failed, long = true)
                    return@registerForActivityResult
                }
                enterResetPasswordMode()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        isSetupMode = !AuthManager.isSetup(this)
        configureMode()
        if (savedInstanceState?.getBoolean(KEY_RESET_PASSWORD_MODE, false) == true) {
            // Verification already happened before the rotation;
            // restore only the new-password UI state.
            enterResetPasswordMode()
        }
        onBackPressedDispatcher.addCallback(this) {
            // Back in the new-password state returns to sign-in
            // instead of leaving the screen; elsewhere it is the
            // normal back press.
            if (isResetPasswordMode) {
                exitResetPasswordMode()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        binding.linkGoogleButton.setOnClickListener {
            googleChooserPurpose = GoogleChooserPurpose.SETUP_LINK
            openGoogleChooser()
        }
        binding.googleButton.setOnClickListener {
            googleChooserPurpose = GoogleChooserPurpose.SIGN_IN
            openGoogleChooser()
        }
        binding.forgotPasswordButton.setOnClickListener { onForgotPassword() }
        binding.primaryButton.setOnClickListener {
            when {
                isResetPasswordMode -> saveNewPassword()
                isSetupMode -> saveSetup()
                else -> signIn()
            }
        }
        binding.resetButton.setOnClickListener { confirmReset() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_RESET_PASSWORD_MODE, isResetPasswordMode)
    }

    override fun onSupportNavigateUp(): Boolean {
        if (isResetPasswordMode) {
            exitResetPasswordMode()
            return true
        }
        finish()
        return true
    }

    private fun configureMode() {
        if (isSetupMode) {
            supportActionBar?.setTitle(R.string.set_up_owner_sign_in)
            binding.introText.setText(R.string.owner_setup_intro)
            binding.confirmLayout.visibility = View.VISIBLE
            binding.linkGoogleButton.visibility = View.VISIBLE
            binding.googleButton.visibility = View.GONE
            binding.resetButton.visibility = View.GONE
            binding.forgotPasswordButton.visibility = View.GONE
            binding.primaryButton.setText(R.string.save_owner_sign_in)
            linkedGoogleEmail = null
            binding.googleLinkedLabel.visibility = View.GONE
        } else {
            supportActionBar?.setTitle(R.string.owner_sign_in)
            binding.introText.setText(R.string.owner_signin_intro)
            binding.confirmLayout.visibility = View.GONE
            binding.linkGoogleButton.visibility = View.GONE
            binding.googleLinkedLabel.visibility = View.GONE
            binding.googleButton.visibility = View.VISIBLE
            binding.resetButton.visibility = View.VISIBLE
            binding.forgotPasswordButton.visibility = View.VISIBLE
            binding.primaryButton.setText(R.string.sign_in)
        }
    }

    /** Forgot password, Path A entry: a linked Google account
     * exists, so ownership is proved with the device account
     * chooser (same openGoogleChooser() as sign-in). Path B — no
     * Google account linked — explains that the only way is the
     * reset and offers it; no email/SMS link exists or is built. */
    private fun onForgotPassword() {
        if (AuthManager.getLinkedGoogleEmail(this) != null) {
            googleChooserPurpose = GoogleChooserPurpose.FORGOT_PASSWORD
            openGoogleChooser()
        } else {
            showForgotPasswordNoGoogleDialog()
        }
    }

    private fun showForgotPasswordNoGoogleDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.forgot_password_no_google_title)
            .setMessage(R.string.forgot_password_no_google_message)
            .setPositiveButton(R.string.reset_owner_confirm) { _, _ -> confirmReset() }
            .setNegativeButton(R.string.admin_cancel, null)
            .show()
            .also { styleDialogButtons(it, destructive = true) }
    }

    /** Enters the "set new password" state after the linked Google
     * account matched: reuses the existing password/confirm fields,
     * hides the email field and the sign-in actions, and retitles
     * the screen + primary button. The setup is preserved. */
    private fun enterResetPasswordMode() {
        isResetPasswordMode = true
        supportActionBar?.setTitle(R.string.set_new_password)
        binding.introText.setText(R.string.set_new_password_intro)
        binding.emailLayout.visibility = View.GONE
        binding.emailLayout.error = null
        binding.passwordLayout.hint = getString(R.string.new_password)
        binding.passwordLayout.error = null
        binding.passwordInput.text?.clear()
        binding.confirmLayout.visibility = View.VISIBLE
        binding.confirmLayout.error = null
        binding.confirmInput.text?.clear()
        binding.linkGoogleButton.visibility = View.GONE
        binding.googleLinkedLabel.visibility = View.GONE
        binding.googleButton.visibility = View.GONE
        binding.resetButton.visibility = View.GONE
        binding.forgotPasswordButton.visibility = View.GONE
        binding.primaryButton.setText(R.string.save_new_password)
    }

    /** Leaves the "set new password" state without saving anything
     * — back to plain sign-in, old password untouched. */
    private fun exitResetPasswordMode() {
        isResetPasswordMode = false
        binding.passwordInput.text?.clear()
        binding.confirmInput.text?.clear()
        binding.passwordLayout.error = null
        binding.confirmLayout.error = null
        configureMode()
        binding.passwordLayout.hint = getString(R.string.password)
        binding.emailLayout.visibility = View.VISIBLE
    }

    /** Saves the new password after Google-account verification:
     * same 8+ char + confirm-match validation as setup, spinner
     * while saving (same posted pattern as signIn(), C-55). On
     * success the owner is signed in (changePassword sets the
     * session) and the activity finishes with RESULT_OK. */
    private fun saveNewPassword() {
        val password = binding.passwordInput.text?.toString().orEmpty()
        val confirm = binding.confirmInput.text?.toString().orEmpty()
        var valid = true
        if (password.length < AuthManager.MIN_PASSWORD_LENGTH) {
            binding.passwordLayout.error = getString(R.string.password_too_short)
            valid = false
        } else {
            binding.passwordLayout.error = null
        }
        if (confirm != password) {
            binding.confirmLayout.error = getString(R.string.passwords_dont_match)
            valid = false
        } else {
            binding.confirmLayout.error = null
        }
        if (!valid) return
        setAuthLoading(true)
        binding.root.post {
            val ok = AuthManager.changePassword(this, password)
            setAuthLoading(false)
            if (ok) {
                ToastHelper.show(this, ToastHelper.Type.SUCCESS, R.string.password_changed)
                setResult(RESULT_OK)
                finish()
            } else {
                ToastHelper.show(this, ToastHelper.Type.ERROR, R.string.password_change_failed)
            }
        }
    }

    private fun openGoogleChooser() {
        val intent = AccountManager.newChooseAccountIntent(
            null,
            null,
            arrayOf("com.google"),
            true,
            null,
            null,
            null,
            null,
        )
        googleChooserLauncher.launch(intent)
    }

    private fun saveSetup() {
        val email = binding.emailInput.text?.toString().orEmpty()
        val password = binding.passwordInput.text?.toString().orEmpty()
        val confirm = binding.confirmInput.text?.toString().orEmpty()
        var valid = true
        if (!AuthManager.isEmailValid(email)) {
            binding.emailLayout.error = getString(R.string.invalid_email)
            valid = false
        } else {
            binding.emailLayout.error = null
        }
        if (password.length < AuthManager.MIN_PASSWORD_LENGTH) {
            binding.passwordLayout.error = getString(R.string.password_too_short)
            valid = false
        } else {
            binding.passwordLayout.error = null
        }
        if (confirm != password) {
            binding.confirmLayout.error = getString(R.string.passwords_dont_match)
            valid = false
        } else {
            binding.confirmLayout.error = null
        }
        if (!valid) return
        if (AuthManager.setupOwner(this, email, password, linkedGoogleEmail)) {
            ToastHelper.show(
                this, ToastHelper.Type.SUCCESS,
                getString(R.string.signed_in_as, AuthManager.getOwnerEmail(this).orEmpty()),
            )
            setResult(RESULT_OK)
            finish()
        } else {
            ToastHelper.show(this, ToastHelper.Type.ERROR, R.string.invalid_email)
        }
    }

    private fun signIn() {
        val email = binding.emailInput.text?.toString().orEmpty()
        val password = binding.passwordInput.text?.toString().orEmpty()
        // Sign-in is a synchronous local hash check, so run it
        // posted: the spinner gets a frame on screen (C-55) before
        // the check runs, then hides on completion or error.
        setAuthLoading(true)
        binding.root.post {
            val ok = AuthManager.signInWithPassword(this, email, password)
            setAuthLoading(false)
            if (ok) {
                ToastHelper.show(
                    this, ToastHelper.Type.SUCCESS,
                    getString(R.string.signed_in_as, AuthManager.getOwnerEmail(this).orEmpty()),
                )
                setResult(RESULT_OK)
                finish()
            } else {
                binding.passwordLayout.error = getString(R.string.wrong_email_or_password)
                ToastHelper.show(this, ToastHelper.Type.ERROR, R.string.wrong_email_or_password)
            }
        }
    }

    /** Auth loading spinner (C-55, S-08 §3): the ProgressBar shows
     * while signing in; the sign-in button, the Google / reset
     * buttons and all inputs are disabled until it hides. Setup
     * (saveSetup) is untouched — this is the sign-in path only. */
    private fun setAuthLoading(loading: Boolean) {
        binding.authLoading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.primaryButton.isEnabled = !loading
        binding.googleButton.isEnabled = !loading
        binding.resetButton.isEnabled = !loading
        binding.forgotPasswordButton.isEnabled = !loading
        binding.linkGoogleButton.isEnabled = !loading
        binding.emailInput.isEnabled = !loading
        binding.passwordInput.isEnabled = !loading
        binding.confirmInput.isEnabled = !loading
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_owner_sign_in)
            .setMessage(R.string.reset_owner_message)
            .setPositiveButton(R.string.reset_owner_confirm) { _, _ ->
                AuthManager.resetOwner(this)
                ToastHelper.show(this, ToastHelper.Type.SUCCESS, R.string.owner_reset_done)
                binding.emailInput.text?.clear()
                binding.passwordInput.text?.clear()
                binding.confirmInput.text?.clear()
                isSetupMode = true
                isResetPasswordMode = false
                binding.passwordLayout.hint = getString(R.string.password)
                binding.emailLayout.visibility = View.VISIBLE
                configureMode()
            }
            .setNegativeButton(R.string.admin_cancel, null)
            .show()
            .also { styleDialogButtons(it, destructive = true) }
    }
}
