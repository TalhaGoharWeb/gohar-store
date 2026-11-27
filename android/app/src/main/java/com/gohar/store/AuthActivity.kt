package com.gohar.store

import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
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
 * On success this activity finishes with RESULT_OK, and MainActivity
 * refreshes its menu so the developer console appears.
 */
class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private var isSetupMode = true
    private var linkedGoogleEmail: String? = null

    private val googleChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            Toast.makeText(this, R.string.choose_google_account, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME).orEmpty()
        if (email.isBlank()) {
            Toast.makeText(this, R.string.google_sign_in_failed, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        if (isSetupMode) {
            linkedGoogleEmail = email
            binding.googleLinkedLabel.text = getString(R.string.google_account_linked, email)
            binding.googleLinkedLabel.visibility = View.VISIBLE
        } else {
            if (AuthManager.getLinkedGoogleEmail(this) == null) {
                Toast.makeText(this, R.string.no_google_linked, Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            if (AuthManager.signInWithGoogle(this, email)) {
                Toast.makeText(
                    this,
                    getString(R.string.signed_in_as, email),
                    Toast.LENGTH_SHORT,
                ).show()
                setResult(RESULT_OK)
                finish()
            } else {
                Toast.makeText(this, R.string.google_sign_in_failed, Toast.LENGTH_LONG).show()
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

        binding.linkGoogleButton.setOnClickListener { openGoogleChooser() }
        binding.googleButton.setOnClickListener { openGoogleChooser() }
        binding.primaryButton.setOnClickListener {
            if (isSetupMode) saveSetup() else signIn()
        }
        binding.resetButton.setOnClickListener { confirmReset() }
    }

    override fun onSupportNavigateUp(): Boolean {
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
            binding.primaryButton.setText(R.string.sign_in)
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
            Toast.makeText(
                this,
                getString(R.string.signed_in_as, AuthManager.getOwnerEmail(this).orEmpty()),
                Toast.LENGTH_SHORT,
            ).show()
            setResult(RESULT_OK)
            finish()
        } else {
            Toast.makeText(this, R.string.invalid_email, Toast.LENGTH_SHORT).show()
        }
    }

    private fun signIn() {
        val email = binding.emailInput.text?.toString().orEmpty()
        val password = binding.passwordInput.text?.toString().orEmpty()
        if (AuthManager.signInWithPassword(this, email, password)) {
            Toast.makeText(
                this,
                getString(R.string.signed_in_as, AuthManager.getOwnerEmail(this).orEmpty()),
                Toast.LENGTH_SHORT,
            ).show()
            setResult(RESULT_OK)
            finish()
        } else {
            binding.passwordLayout.error = getString(R.string.wrong_email_or_password)
            Toast.makeText(this, R.string.wrong_email_or_password, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_owner_sign_in)
            .setMessage(R.string.reset_owner_message)
            .setPositiveButton(R.string.reset_owner_confirm) { _, _ ->
                AuthManager.resetOwner(this)
                Toast.makeText(this, R.string.owner_reset_done, Toast.LENGTH_SHORT).show()
                binding.emailInput.text?.clear()
                binding.passwordInput.text?.clear()
                binding.confirmInput.text?.clear()
                isSetupMode = true
                configureMode()
            }
            .setNegativeButton(R.string.admin_cancel, null)
            .show()
    }
}
