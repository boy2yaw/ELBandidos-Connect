package com.boy2yaw.elbndds

import android.os.Bundle
import android.graphics.Color
import android.graphics.BitmapFactory
import android.net.Uri
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import android.text.Editable
import android.text.TextWatcher
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.content.res.ColorStateList
import android.widget.*
import android.widget.FrameLayout
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private val db by lazy { FirebaseFirestore.getInstance() }
    private lateinit var root: LinearLayout
    private lateinit var appPageContainer: LinearLayout
    private var messagesListener: ListenerRegistration? = null
    private var membersListener: ListenerRegistration? = null
    private var membersHidden = false
    private var selectedProfileImage: Uri? = null
    private var pendingChatImageConversationId: String? = null
    private var pendingChatImageFile: File? = null
    private var currentPageKey = "CHAT"
    private val pageHistory = mutableListOf<String>()
    // OpenDocument grants persistable read access, unlike GetContent's temporary URI grant.
    private val chatImagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val conversationId = pendingChatImageConversationId
        pendingChatImageConversationId = null
        if (uri != null && conversationId != null) {
            val cachedImage = copyChatImageToCache(uri)
            if (cachedImage == null) {
                toast("Cannot read selected image. Please choose another photo or check its access.")
            } else if (conversationId == currentConversationId) {
                pendingChatImageFile?.delete()
                pendingChatImageFile = cachedImage
                showConversation() // Render the selected photo in the composer; do not upload yet.
            } else {
                cachedImage.delete()
                toast("Photo selection cancelled because the conversation changed.")
            }
        }
    }
    private val profileImagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            retainSelectedImageAccess(uri)
            selectedProfileImage = uri
            showProfile()
        }
    }

    private fun retainSelectedImageAccess(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Some document providers do not support persistence; keep using the active grant.
        }
    }

    // Sales Point-inspired theme: dark teal / clean light surfaces with a consistent teal accent.
    private val bg: Int get() = if (lightTheme) Color.rgb(245, 248, 249) else Color.rgb(6, 28, 32)
    private val panel: Int get() = if (lightTheme) Color.WHITE else Color.rgb(13, 48, 53)
    private val teal: Int get() = Color.rgb(0, 119, 127)
    private val white: Int get() = if (lightTheme) Color.rgb(30, 43, 48) else Color.rgb(232, 245, 244)
    private val muted: Int get() = if (lightTheme) Color.rgb(94, 111, 117) else Color.rgb(151, 184, 183)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lightTheme = getSharedPreferences("elbandidos_preferences", MODE_PRIVATE)
            .getBoolean("light_theme", false)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        updateSystemBarAppearance()
        auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            updatePresence(true)
            showChat()
        } else showLogin()
    }

    override fun onStart() {
        super.onStart()
        if (::auth.isInitialized && auth.currentUser != null) {
            updatePresence(true)
            val enabled = getSharedPreferences("elbandidos_preferences", MODE_PRIVATE)
                .getBoolean("location_sharing_enabled", true)
            if (enabled && locationListener == null) startLocationSharing()
        }
    }

    override fun onStop() {
        if (::auth.isInitialized && auth.currentUser != null) updatePresence(false)
        super.onStop()
    }

    override fun onDestroy() {
        messagesListener?.remove()
        membersListener?.remove()
        super.onDestroy()
    }

    private fun updatePresence(online: Boolean) {
        val user = auth.currentUser ?: return
        val data = hashMapOf<String, Any>(
            "displayName" to (user.displayName ?: user.email ?: "Member"),
            "updatedAt" to FieldValue.serverTimestamp(),
            "isOnline" to online
        )
        db.collection("members").document(user.uid).set(data, com.google.firebase.firestore.SetOptions.merge())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(12).toFloat()
            if (stroke > 0) setStroke(dp(1), teal)
        }

    // Shared rounded surfaces used by every dialog and popup in the app.
    private fun dialogShape(color: Int, strokeColor: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(22).toFloat()
            if (strokeColor != Color.TRANSPARENT) setStroke(dp(1), strokeColor)
        }

    private fun styleDialogInput(view: View) {
        if (view is EditText) {
            view.setTextColor(white)
            view.setHintTextColor(muted)
            view.background = dialogShape(if (lightTheme) Color.rgb(248, 250, 250) else bg, teal)
            view.setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) styleDialogInput(view.getChildAt(i))
        }
    }

    private fun styleDialog(dialog: android.app.AlertDialog) {
        val dialogBg = if (lightTheme) Color.rgb(248, 250, 250) else Color.rgb(13, 48, 53)
        val accent = teal
        val text = if (lightTheme) Color.rgb(24, 42, 44) else Color.rgb(232, 245, 244)
        val mutedText = if (lightTheme) Color.rgb(94, 111, 117) else Color.rgb(151, 184, 183)

        dialog.window?.apply {
            setBackgroundDrawable(dialogShape(dialogBg, accent))
            setDimAmount(0.72f)
            // Native fade/scale animation when confirmation and action dialogs appear.
            setWindowAnimations(android.R.style.Animation_Dialog)
        }

        val alertTitleId = resources.getIdentifier("alertTitle", "id", "android")
        if (alertTitleId != 0) {
            dialog.findViewById<TextView>(alertTitleId)?.apply {
                setTextColor(accent)
                textSize = 19f
                setTypeface(null, Typeface.BOLD)
            }
        }

        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            setTextColor(text)
            textSize = 15f
            setLineSpacing(dp(2).toFloat(), 1f)
        }

        dialog.findViewById<ListView>(android.R.id.list)?.apply {
            divider = android.graphics.drawable.ColorDrawable(Color.rgb(32, 74, 78))
            dividerHeight = dp(1)
            setBackgroundColor(dialogBg)
        }

        (dialog.window?.decorView as? ViewGroup)?.background = dialogShape(dialogBg, accent)

        val positive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        val negative = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
        val neutral = dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)

        positive?.apply {
            // Explicit tint prevents Android's light-theme button tint from
            // washing out the custom background and making white text invisible.
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = dialogShape(accent)
            backgroundTintList = ColorStateList.valueOf(accent)
            setPadding(dp(18), dp(9), dp(18), dp(9))
        }

        listOf(negative, neutral).forEach { button ->
            button?.apply {
                setTextColor(if (lightTheme) Color.rgb(48, 65, 68) else mutedText)
                setTypeface(null, Typeface.BOLD)
                val buttonBg = if (lightTheme) Color.rgb(230, 239, 239) else Color.rgb(16, 53, 58)
                val buttonStroke = if (lightTheme) Color.rgb(145, 174, 174) else Color.rgb(40, 99, 106)
                background = dialogShape(buttonBg, buttonStroke)
                backgroundTintList = ColorStateList.valueOf(buttonBg)
                setPadding(dp(16), dp(9), dp(16), dp(9))
            }
        }
    }
    private fun showRoundedPopup(anchor: View, items: List<Pair<String, () -> Unit>>) {
        val popupBg = if (lightTheme) Color.rgb(248, 250, 250) else Color.rgb(13, 48, 53)
        val popupText = if (lightTheme) Color.rgb(24, 42, 44) else Color.rgb(232, 245, 244)
        val popupStroke = if (lightTheme) Color.rgb(170, 190, 185) else Color.rgb(40, 99, 106)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = dialogShape(popupBg, teal)
            clipToOutline = true
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }

        lateinit var popup: PopupWindow
        items.forEach { (label, action) ->
            val row = TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(popupText)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(13), dp(16), dp(13))
                background = dialogShape(popupBg)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    popup.dismiss()
                    action()
                }
            }
            content.addView(row, LinearLayout.LayoutParams(-1, -2))
        }

        popup = PopupWindow(
            content,
            dp(240),
            WindowManager.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(dialogShape(popupBg, popupStroke))
            elevation = dp(12).toFloat()
            isOutsideTouchable = true
            isClippingEnabled = true
        }

        content.clipToOutline = true
        popup.animationStyle = android.R.style.Animation_Dialog
        popup.showAsDropDown(anchor, -dp(8), dp(4), Gravity.END)
        content.alpha = 0f
        content.translationY = -dp(8).toFloat()
        content.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

        // Smooth, lightweight entrance used by login/register and app pages.
    private fun animatePageEntrance(view: View) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = dp(12).toFloat()
        view.scaleX = 0.99f
        view.scaleY = 0.99f
        view.post {
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(260L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun setupScreen() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            clipToPadding = false
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            // Apply insets directly to the actual content column. This avoids
            // the logo/header being laid out underneath the status bar.
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                view.setPadding(
                    dp(20),
                    dp(16) + bars.top,
                    dp(20),
                    dp(16) + maxOf(bars.bottom, ime.bottom)
                )
                insets
            }
        }
        scroll.addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(scroll)
        ViewCompat.requestApplyInsets(root)
        animatePageEntrance(root)
    }

    private fun addLogo() {
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.elbandidos_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        root.addView(logo, LinearLayout.LayoutParams(dp(145), dp(145)).apply {
            bottomMargin = dp(8)
        })
    }

    private fun addBrand() {
        addText("EL BANDIDOS", 26f, white, true)
        addText("CONNECT", 16f, teal, true)
        addText("RIDE TOGETHER. STAY CONNECTED.", 10f, muted, false)
    }

    private fun addCompactHeader() {
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.elbandidos_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        root.addView(logo, LinearLayout.LayoutParams(dp(104), dp(104)).apply {
            bottomMargin = dp(2)
        })
        addText("EL BANDIDOS", 24f, white, true)
        addText("CONNECT", 15f, teal, true)
        addText("RIDE TOGETHER. STAY CONNECTED.", 9f, muted, false)
    }

    private fun addText(value: String, size: Float, color: Int, bold: Boolean) {
        val view = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) setTypeface(null, Typeface.BOLD)
        }
        root.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(3), 0, dp(3)) })
    }

    private fun addInput(hint: String, type: Int): EditText {
        val input = EditText(this).apply {
            this.hint = hint
            textSize = 15f
            setTextColor(white)
            setHintTextColor(muted)
            inputType = type
            isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(panel, 1)
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        root.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
        ).apply { setMargins(0, dp(7), 0, dp(7)) })
        return input
    }

    private fun addButton(label: String, primary: Boolean = true, action: () -> Unit) {
        val button = TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (primary) bg else teal)
            background = rounded(if (primary) teal else panel, if (primary) 0 else 1)
            setOnClickListener {
                animate().cancel()
                scaleX = 0.98f
                scaleY = 0.98f
                animate().scaleX(1f).scaleY(1f).setDuration(150L).start()
                action()
            }
        }
        root.addView(button, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(54)
        ).apply { setMargins(0, dp(7), 0, dp(7)) })
    }

    private fun addShowPassword(password: EditText) {
        val check = CheckBox(this).apply {
            text = "Show password"
            textSize = 13f
            setTextColor(muted)
            buttonTintList = ColorStateList.valueOf(teal)
            setOnCheckedChangeListener { _, checked ->
                val pos = password.selectionStart
                password.inputType = if (checked)
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                password.setSelection(pos.coerceIn(0, password.text.length))
            }
        }
        root.addView(check)
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun showLogin() {
        messagesListener?.remove()
        membersListener?.remove()
        setupScreen()
        addLogo()
        addBrand()
        addText("MEMBER LOGIN", 15f, white, true)
        val email = addInput("Email address",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val password = addInput("Password",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        password.imeOptions = EditorInfo.IME_ACTION_DONE
        addShowPassword(password)

        val forgot = TextView(this).apply {
            text = "Forgot password?"
            textSize = 13f
            setTextColor(teal)
            gravity = Gravity.END
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener {
                val address = email.text.toString().trim()
                if (address.isEmpty()) {
                    toast("Enter your email address first.")
                    email.requestFocus()
                } else auth.sendPasswordResetEmail(address)
                    .addOnSuccessListener { toast("Password reset email sent.") }
                    .addOnFailureListener { toast(it.message ?: "Unable to send reset email.") }
            }
        }
        root.addView(forgot, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(40)
        ))
        addButton("LOGIN") {
            val e = email.text.toString().trim()
            val p = password.text.toString()
            if (e.isEmpty() || p.isEmpty()) {
                toast("Please enter your email and password.")
                return@addButton
            }
            hideKeyboard()
            auth.signInWithEmailAndPassword(e, p)
                .addOnSuccessListener {
                    pageHistory.clear(); currentPageKey = "CHAT"
                    updatePresence(true)
                    showChat()
                }
                .addOnFailureListener { toast(it.message ?: "Login failed.") }
        }
        addButton("CREATE ACCOUNT", false) { showRegister() }
        addText("EL BANDIDOS RIDERS CLUB", 11f, muted, false)
    }

    private fun showRegister() {
        setupScreen()
        addLogo()
        addBrand()
        addText("CREATE ACCOUNT", 17f, white, true)
        val name = addInput("Display name",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val email = addInput("Email address",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val password = addInput("Password (minimum 6 characters)",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        addShowPassword(password)
        addButton("REGISTER") {
            val n = name.text.toString().trim()
            val e = email.text.toString().trim()
            val p = password.text.toString()
            if (n.isEmpty() || e.isEmpty() || p.length < 6) {
                toast("Complete all fields. Password must have at least 6 characters.")
                return@addButton
            }
            hideKeyboard()
            auth.createUserWithEmailAndPassword(e, p)
                .addOnSuccessListener {
                    val profile = UserProfileChangeRequest.Builder().setDisplayName(n).build()
                    auth.currentUser?.updateProfile(profile)
                        ?.addOnSuccessListener {
                            updatePresence(true)
                            showChat()
                        }
                        ?.addOnFailureListener { toast(it.message ?: "Profile update failed.") }
                }
                .addOnFailureListener { toast(it.message ?: "Registration failed.") }
        }
        addButton("BACK TO LOGIN", false) { showLogin() }
    }


    private var activeTab = "CHAT"
    private var lightTheme = false
    private var locationSharing = false
    private var currentConversationId = "main"
    private var currentConversationTitle = "Group Chat"
    private var currentConversationIsGroup = false
    private var locationManager: android.location.LocationManager? = null
    private var locationListener: android.location.LocationListener? = null

    private val pageBg: Int get() = bg
    private val pagePanel: Int get() = panel
    private val pageText: Int get() = white
    private val pageMuted: Int get() = muted

    private fun updateSystemBarAppearance() {
        window.statusBarColor = pageBg
        window.navigationBarColor = pageBg
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = lightTheme
        controller.isAppearanceLightNavigationBars = lightTheme
    }

    private fun saveThemePreference() {
        getSharedPreferences("elbandidos_preferences", MODE_PRIVATE)
            .edit().putBoolean("light_theme", lightTheme).apply()
        updateSystemBarAppearance()
    }

    private fun addTopBar(title: String = "El Bandidos Connect") {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.elbandidos_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        bar.addView(logo, LinearLayout.LayoutParams(dp(42), dp(42)))
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), 0, 0, 0)
        }
        names.addView(TextView(this).apply {
            text = "EL BANDIDOS"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(pageText)
        })
        names.addView(TextView(this).apply {
            text = "CONNECT"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(teal)
        })
        bar.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        val menu = TextView(this).apply {
            text = "⋮"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(pageText)
            setOnClickListener { showSettingsMenu(this) }
        }
        bar.addView(menu, LinearLayout.LayoutParams(dp(42), dp(46)))
        root.addView(bar)
        if (title != "El Bandidos Connect") {
            val heading = TextView(this).apply {
                text = title
                textSize = 19f
                setTypeface(null, Typeface.BOLD)
                setTextColor(pageText)
                setPadding(0, dp(3), 0, dp(10))
            }
            root.addView(heading)
        }
    }

    private fun showSettingsMenu(anchor: android.view.View) {
        showRoundedPopup(anchor, listOf(
            "Settings" to { navigateTo("SETTINGS") },
            (if (lightTheme) "Switch to Dark Theme" else "Switch to Light Theme") to {
                lightTheme = !lightTheme
                saveThemePreference()
                showCurrentTab()
            },
            "Log out" to {
                stopLocationUpdates()
                updatePresence(false)
                auth.signOut()
                showLogin()
            }
        ))
    }

        private fun setupAppPage(fixedContent: Boolean = false) {
        updateSystemBarAppearance()
        appPageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBg)
            setPadding(0, 0, 0, dp(4))
        }
        // Android 15+ enforces edge-to-edge for targetSdk 35 and newer.
        // Apply system-bar/cutout insets to this shared container exactly once.
        ViewCompat.setOnApplyWindowInsetsListener(appPageContainer) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // Keep the page content and chat composer above the keyboard.
            val bottomInset = maxOf(bars.bottom, ime.bottom)
            view.setPadding(0, bars.top, 0, bottomInset + dp(4))
            insets
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }

        if (fixedContent) {
            // Conversation header/composer stay fixed; only the message list scrolls.
            appPageContainer.addView(root, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        } else {
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                setBackgroundColor(pageBg)
                clipToPadding = false
                isVerticalScrollBarEnabled = false
            }
            scroll.addView(root, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            // Other pages retain their normal page scrolling; bottom navigation stays fixed.
            appPageContainer.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }
        setContentView(appPageContainer)
        animatePageEntrance(appPageContainer)
        // Request after attachment; otherwise the first tab can miss the inset
        // dispatch and later tabs may appear at a different vertical position.
        ViewCompat.requestApplyInsets(appPageContainer)
    }

    private fun addBottomNavigation() {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, dp(3))
            background = rounded(pagePanel)
        }
        val tabs = listOf(
            Triple("CHAT", "☏", "CHAT"),
            Triple("MEMBERS", "♙", "MEMBERS"),
            Triple("TRACK", "⌖", "TRACK"),
            Triple("PROFILE", "●", "PROFILE")
        )
        tabs.forEach { (label, glyph, tab) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, dp(3))
                setOnClickListener { pageHistory.clear(); navigateTo(tab) }
            }
            item.addView(TextView(this).apply {
                text = glyph
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(if (activeTab == tab) teal else pageMuted)
            })
            item.addView(TextView(this).apply {
                text = label
                textSize = 9f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (activeTab == tab) teal else pageMuted)
            })
            nav.addView(item, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        appPageContainer.addView(nav, LinearLayout.LayoutParams(-1, dp(54)).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
    }

    private fun navigateTo(page: String) {
        if (currentPageKey != page) pageHistory.add(currentPageKey)
        currentPageKey = page
        renderPage(page)
    }

    private fun renderPage(page: String) {
        when (page) {
            "CHAT" -> { activeTab = "CHAT"; showChat() }
            "MEMBERS" -> { activeTab = "MEMBERS"; showMembers() }
            "TRACK" -> { activeTab = "TRACK"; showTrack() }
            "PROFILE" -> { activeTab = "PROFILE"; showProfile() }
            "NEW_CHAT" -> showNewChat()
            "CONVERSATION" -> showConversation()
            "SETTINGS" -> showSettings()
        }
    }

    private fun goBack() {
        if (pageHistory.isNotEmpty()) {
            currentPageKey = pageHistory.removeAt(pageHistory.lastIndex)
            renderPage(currentPageKey)
        } else if (currentPageKey != "CHAT") {
            currentPageKey = "CHAT"
            activeTab = "CHAT"
            showChat()
        } else {
            finish()
        }
    }

    override fun onBackPressed() {
        if (currentPageKey == "CHAT" && pageHistory.isEmpty()) {
            super.onBackPressed()
        } else goBack()
    }

    private fun showCurrentTab() {
        when (activeTab) {
            "CHAT" -> showChat()
            "MEMBERS" -> showMembers()
            "TRACK" -> showTrack()
            "PROFILE" -> showProfile()
        }
    }

    private fun showChat() {
        messagesListener?.remove()
        membersListener?.remove()
        setupAppPage()
        addTopBar()
        val title = TextView(this).apply {
            text = "Chats"
            textSize = 21f
            setTypeface(null, Typeface.BOLD)
            setTextColor(pageText)
        }
        root.addView(title)
        val subtitle = TextView(this).apply {
            text = "Your conversations"
            textSize = 12f
            setTextColor(pageMuted)
            setPadding(0, dp(2), 0, dp(12))
        }
        root.addView(subtitle)

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        fun addMainGroupRow() {
            addConversationRow(list, "Group Chat", "El Bandidos members group", "GROUP", {
                openConversation("main", "Group Chat")
            }, "main")
        }
        addMainGroupRow()

        val myId = auth.currentUser?.uid ?: ""
        membersListener = db.collection("chats")
            .whereArrayContains("members", myId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    toast(error.message ?: "Unable to load chats.")
                    return@addSnapshotListener
                }
                if (activeTab != "CHAT") return@addSnapshotListener

                list.removeAllViews()
                addMainGroupRow()

                val conversations = snapshot?.documents.orEmpty()
                    .filter { doc ->
                        val hiddenFor = doc.get("hiddenFor") as? List<*> ?: emptyList<Any>()
                        myId !in hiddenFor
                    }
                    .sortedByDescending { doc ->
                        doc.getTimestamp("lastMessageAt")?.toDate()?.time
                            ?: doc.getTimestamp("updatedAt")?.toDate()?.time
                            ?: doc.getTimestamp("createdAt")?.toDate()?.time
                            ?: 0L
                    }

                conversations.forEach { doc ->
                    val members = doc.get("members") as? List<*> ?: emptyList<Any>()
                    val isGroup = doc.getString("type") == "GROUP" || doc.id.startsWith("group_")
                    val fallbackName = doc.getString("title")
                        ?: if (isGroup) "Group Chat" else "Private chat"
                    val preview = doc.getString("lastMessage") ?: "Tap to open conversation"
                    if (isGroup) {
                        addConversationRow(list, fallbackName, preview, "GROUP",
                            { openConversation(doc.id, fallbackName, true) }, doc.id)
                    } else {
                        val otherId = members.filterIsInstance<String>().firstOrNull { it != myId }
                        if (otherId != null) {
                            db.collection("members").document(otherId).get()
                                .addOnSuccessListener { profile ->
                                    if (isFinishing || activeTab != "CHAT") return@addOnSuccessListener
                                    val memberName = profile.getString("displayName") ?: fallbackName
                                    val photo = profile.getString("photoUrl")
                                        ?: profile.getString("photoURL")
                                    addConversationRow(list, memberName, preview, "PRIVATE",
                                        { openConversation(doc.id, memberName, false) }, doc.id,
                                        photoUrl = photo)
                                }
                                .addOnFailureListener {
                                    if (isFinishing || activeTab != "CHAT") return@addOnFailureListener
                                    addConversationRow(list, fallbackName, preview, "PRIVATE",
                                        { openConversation(doc.id, fallbackName, false) }, doc.id)
                                }
                        }
                    }
                }
            }

        val spacer = android.widget.FrameLayout(this)
        val fab = TextView(this).apply {
            text = "+  New Chat"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(bg)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(teal); cornerRadius = dp(28).toFloat()
            }
            elevation = dp(6).toFloat()
            setPadding(dp(18), 0, dp(18), 0)
            setOnClickListener { navigateTo("NEW_CHAT") }
        }
        spacer.addView(fab, android.widget.FrameLayout.LayoutParams(-2, dp(52), Gravity.END or Gravity.BOTTOM))
        root.addView(spacer, LinearLayout.LayoutParams(-1, dp(58)))
        addBottomNavigation()
    }

    private fun createAvatar(name: String, imageUrl: String?, sizeDp: Int, backgroundColor: Int = teal): FrameLayout {
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
            }
            clipToOutline = true
        }
        val initial = TextView(this).apply {
            text = name.trim().take(1).uppercase().ifBlank { "M" }
            textSize = (sizeDp * 0.34f).coerceAtLeast(14f)
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(bg)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(backgroundColor)
            }
        }
        frame.addView(initial, FrameLayout.LayoutParams(-1, -1))
        image.visibility = View.GONE
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        if (!imageUrl.isNullOrBlank()) {
            loadProfileImage(imageUrl) { bitmap ->
                if (bitmap != null && !isFinishing) {
                    image.setImageBitmap(bitmap)
                    image.visibility = View.VISIBLE
                    initial.visibility = View.GONE
                }
            }
        }
        return frame
    }

    private fun addConversationRow(parent: LinearLayout, name: String, preview: String,
                                   kind: String, action: () -> Unit, conversationId: String? = null,
                                   photoUrl: String? = null, memberId: String? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(pagePanel)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            setOnClickListener { action() }
        }
        val avatarBg = teal // Solid teal is used only by the default initials avatar; uploaded photos cover it.
        val avatar = createAvatar(if (kind == "GROUP") "EB" else name, photoUrl, 60, avatarBg)
        row.addView(avatar, LinearLayout.LayoutParams(dp(60), dp(60)))
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        details.addView(TextView(this).apply {
            text = name; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(pageText)
        })
        details.addView(TextView(this).apply {
            text = preview; textSize = 12f; setTextColor(if (preview.equals("Online", ignoreCase = true)) teal else pageMuted); maxLines = 1
        })
        row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        if (kind == "PRIVATE" || kind == "GROUP" || kind == "MEMBER") {
            val menu = TextView(this).apply {
                text = "⋮"; textSize = 24f; gravity = Gravity.CENTER
                setTextColor(pageMuted)
                setOnClickListener {
                    if (kind == "MEMBER" && memberId != null)
                        showMemberActions(this, memberId, name)
                    else showConversationRowOptions(this, name, kind, conversationId)
                }
            }
            row.addView(menu, LinearLayout.LayoutParams(dp(36), dp(44)))
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, dp(82)).apply {
            bottomMargin = dp(6)
        })
    }


    // Message bubbles wrap their text naturally but never exceed the available chat width.
    private inner class CappedBubbleLayout(context: Context, private val maxWidthPx: Int) :
        LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = MeasureSpec.getSize(widthMeasureSpec)
            val capped = minOf(available, maxWidthPx)
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(capped, MeasureSpec.AT_MOST),
                heightMeasureSpec
            )
        }
    }

    private fun updateConversationSummary(conversationId: String, preview: String) {
        if (conversationId == "main") return
        db.collection("chats").document(conversationId).update(
            mapOf(
                "lastMessage" to preview,
                "lastMessageAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).addOnFailureListener { error ->
            toast("Message sent, but chat preview could not update: ${error.message ?: "Firestore update failed"}. Check Firestore chat update rules.")
        }
    }

    private fun openPrivateChat(memberId: String, memberName: String) {
        val myId = auth.currentUser?.uid ?: return
        if (memberId == myId) {
            toast("You cannot start a private chat with yourself.")
            return
        }
        val chatId = listOf(myId, memberId).sorted().joinToString("_")
        val chatRef = db.collection("chats").document(chatId)
        chatRef.get()
            .addOnSuccessListener { existing ->
                val writeTask = if (existing.exists()) {
                    // Do not rewrite members/createdAt on an existing conversation.
                    chatRef.update(
                        mapOf(
                            "title" to memberName,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                } else {
                    chatRef.set(
                        mapOf(
                            "members" to listOf(myId, memberId).sorted(),
                            "title" to memberName,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                writeTask.addOnSuccessListener {
                    openConversation(chatId, memberName)
                }.addOnFailureListener { e ->
                    toast("Cannot start chat: ${e.message ?: "Firestore permission denied"}")
                }
            }
            .addOnFailureListener { e ->
                toast("Cannot check conversation: ${e.message ?: "Firestore read failed"}")
            }
    }

    private fun showMemberActions(anchor: View, memberId: String, memberName: String) {
        val prefs = getSharedPreferences("member_actions", MODE_PRIVATE)
        val blocked = prefs.getBoolean("blocked_$memberId", false)
        val muted = prefs.getBoolean("muted_$memberId", false)

        showRoundedPopup(anchor, listOf(
            "Send Message" to { openPrivateChat(memberId, memberName) },
            "Audio Call (coming soon)" to {
                toast("Calling will be enabled after the one-to-one WebRTC service is connected.")
            },
            "Video Call (coming soon)" to {
                toast("Calling will be enabled after the one-to-one WebRTC service is connected.")
            },
            (if (blocked) "Unblock Member" else "Block Member") to {
                val value = !blocked
                prefs.edit().putBoolean("blocked_$memberId", value).apply()
                toast(if (value) "$memberName blocked on this device." else "$memberName unblocked.")
            },
            (if (muted) "Unmute Member" else "Mute Member") to {
                val value = !muted
                prefs.edit().putBoolean("muted_$memberId", value).apply()
                toast(if (value) "$memberName muted on this device." else "$memberName unmuted.")
            },
            "Track Member" to { navigateTo("TRACK") }
        ))
    }

    private fun showConversationRowOptions(anchor: View, title: String, kind: String, conversationId: String?) {
        val id = conversationId ?: return
        val isMuted = getSharedPreferences("conversation_settings", MODE_PRIVATE)
            .getBoolean("muted_$id", false)

        showRoundedPopup(anchor, listOf(
            (if (isMuted) "Unmute conversation" else "Mute conversation") to {
                val mute = !isMuted
                getSharedPreferences("conversation_settings", MODE_PRIVATE).edit()
                    .putBoolean("muted_$id", mute).apply()
                toast(if (mute) "Conversation muted on this device." else "Conversation unmuted.")
                pageHistory.clear()
                currentPageKey = "CHAT"
                showChat()
            },
            "Delete conversation" to { confirmDeleteConversation(id, title) }
        ))
    }

    private fun showConversationOptions(anchor: View) {
        showRoundedPopup(anchor, listOf(
            "Video Call" to {
                toast("Video calling is coming soon.")
            },
            "Clear History" to {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Clear chat history?")
                    .setMessage("Delete all messages and photos in this conversation for everyone? This action cannot be undone.")
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("CLEAR HISTORY") { _, _ ->
                        clearConversationHistory(currentConversationId)
                    }.show().also { styleDialog(it) }
            },
            "Delete Chat" to {
                confirmDeleteConversation(currentConversationId, currentConversationTitle)
            }
        ))
    }

        private fun clearConversationHistory(conversationId: String) {
        val ref = if (conversationId == "main")
            db.collection("groupChats").document("main").collection("messages")
        else db.collection("chats").document(conversationId).collection("messages")
        ref.limit(400).get().addOnSuccessListener { snapshot ->
            if (snapshot.isEmpty) {
                toast("Chat history is already empty.")
                return@addOnSuccessListener
            }
            val batch = db.batch()
            snapshot.documents.forEach { batch.delete(it.reference) }
            batch.commit().addOnSuccessListener {
                clearConversationHistory(conversationId)
            }.addOnFailureListener {
                toast(it.message ?: "Unable to clear history. Firestore delete permission may be required.")
            }
        }.addOnFailureListener {
            toast(it.message ?: "Unable to load chat history.")
        }
    }

    private fun confirmDeleteConversation(id: String, title: String) {
        if (id == "main") {
            toast("The Group Chat cannot be deleted for everyone. You can mute it instead.")
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete entire conversation?")
            .setMessage("This permanently deletes all messages and photos in '$title' for BOTH members. This cannot be undone.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("DELETE FOR BOTH") { _, _ ->
                toast("Deleting conversation history…")
                deleteConversationMessages(id)
            }.show().also { styleDialog(it) }
    }

    // Firestore does not remove subcollections when a parent document is deleted.
    // Delete message documents in safe batches first, then remove the chat document.
    private fun deleteConversationMessages(conversationId: String) {
        val messagesRef = db.collection("chats").document(conversationId).collection("messages")
        messagesRef.limit(400).get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.isEmpty) {
                    db.collection("chats").document(conversationId).delete()
                        .addOnSuccessListener {
                            toast("Entire conversation deleted for both members.")
                            pageHistory.clear()
                            currentPageKey = "CHAT"
                            showChat()
                        }
                        .addOnFailureListener { toast(it.message ?: "Unable to delete conversation.") }
                    return@addOnSuccessListener
                }
                val batch = db.batch()
                snapshot.documents.forEach { batch.delete(it.reference) }
                batch.commit()
                    .addOnSuccessListener { deleteConversationMessages(conversationId) }
                    .addOnFailureListener { toast(it.message ?: "Unable to delete all messages. Check Firebase permissions.") }
            }
            .addOnFailureListener { toast(it.message ?: "Unable to load conversation messages.") }
    }

    // Users can edit or soft-delete only their own messages. Soft deletion keeps a visible note for everyone.
    private fun showOwnMessageActions(messageRef: com.google.firebase.firestore.DocumentReference, body: String, deleted: Boolean) {
        if (deleted) return
        val options = if (body.isNotBlank()) arrayOf("Edit message", "Delete message") else arrayOf("Delete message")
        android.app.AlertDialog.Builder(this)
            .setTitle("Your message")
            .setItems(options) { _, which ->
                val choice = options[which]
                if (choice == "Edit message") {
                    val editor = EditText(this).apply {
                        setText(body)
                        setSelection(text.length)
                        setTextColor(pageText)
                        setHintTextColor(pageMuted)
                    }
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Edit message")
                        .setView(editor)
                        .setNegativeButton("CANCEL", null)
                        .setPositiveButton("SAVE") { _, _ ->
                            val updated = editor.text.toString().trim()
                            if (updated.isBlank()) {
                                toast("Message cannot be empty. Use Delete message instead.")
                            } else {
                                messageRef.update(mapOf(
                                    "text" to updated,
                                    "edited" to true,
                                    "editedAt" to FieldValue.serverTimestamp()
                                )).addOnSuccessListener { toast("Message updated.") }
                                    .addOnFailureListener { toast(it.message ?: "Unable to edit message. Check Firestore update rules.") }
                            }
                        }.show().also { styleDialog(it) }
                } else {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Delete message?")
                        .setMessage("A ‘This message was deleted’ note will remain visible to everyone in this chat.")
                        .setNegativeButton("CANCEL", null)
                        .setPositiveButton("DELETE") { _, _ ->
                            messageRef.update(mapOf(
                                "text" to "",
                                "deleted" to true
                            )).addOnSuccessListener { toast("Message deleted.") }
                                .addOnFailureListener { toast(it.message ?: "Unable to delete message. Check Firestore update rules.") }
                        }.show().also { styleDialog(it) }
                }
            }.show().also { styleDialog(it) }
    }

    private fun openConversation(id: String, title: String, isGroup: Boolean = (id == "main" || id.startsWith("group_"))) {
        currentConversationId = id
        currentConversationTitle = title
        currentConversationIsGroup = isGroup
        navigateTo("CONVERSATION")
    }

    private fun showConversation() {
        messagesListener?.remove()
        membersListener?.remove()
        setupAppPage(fixedContent = true)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(5))
            elevation = dp(12).toFloat()
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(pageText)
            background = rounded(pagePanel)
            setOnClickListener { goBack() }
        }
        top.addView(back, LinearLayout.LayoutParams(dp(48), dp(50)).apply {
            rightMargin = dp(8)
        })

        val identityBlock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(5), dp(8), dp(5))
            background = rounded(pagePanel)
        }
        val avatar = createAvatar(currentConversationTitle, null, 42)
        identityBlock.addView(avatar, LinearLayout.LayoutParams(dp(42), dp(42)))

        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), 0, dp(3), 0)
        }
        identity.addView(TextView(this).apply {
            text = currentConversationTitle
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(pageText)
            maxLines = 1
        })
        val status = TextView(this).apply {
            text = if (currentConversationIsGroup) "Group conversation" else "last seen"
            textSize = 11f
            setTextColor(pageMuted)
        }
        identity.addView(status)
        identityBlock.addView(identity, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(identityBlock, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
            rightMargin = dp(8)
        })

        val actionsBlock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(pagePanel)
            setPadding(dp(3), 0, dp(3), 0)
        }
        val call = TextView(this).apply {
            text = "📞"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(pageText)
            contentDescription = "Audio call"
            setOnClickListener { toast("Audio calling is coming soon.") }
        }
        actionsBlock.addView(call, LinearLayout.LayoutParams(dp(43), dp(50)))
        val options = TextView(this).apply {
            text = "⋮"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(pageText)
            contentDescription = "Conversation options"
            setOnClickListener { showConversationOptions(this) }
        }
        actionsBlock.addView(options, LinearLayout.LayoutParams(dp(38), dp(50)))
        top.addView(actionsBlock, LinearLayout.LayoutParams(-2, dp(54)))
        root.addView(top, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
        })

        // Load the other member's saved photo for private-chat header.
        if (!currentConversationIsGroup && currentConversationId != "main") {
            val members = currentConversationId.split("_")
            val otherId = members.firstOrNull { it != auth.currentUser?.uid }
            if (otherId != null) {
                db.collection("members").document(otherId).get().addOnSuccessListener { profile ->
                    val url = profile.getString("photoUrl")
                    val display = profile.getString("displayName") ?: currentConversationTitle
                    val isOnline = profile.getBoolean("isOnline") == true
                    status.text = if (isOnline) "online" else "last seen"
                    status.setTextColor(if (isOnline) teal else pageMuted)
                    if (!url.isNullOrBlank()) {
                        loadProfileImage(url) { bitmap ->
                            if (bitmap != null && !isFinishing) {
                                avatar.removeAllViews()
                                val photo = ImageView(this).apply {
                                    setImageBitmap(bitmap)
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                    clipToOutline = true
                                    background = GradientDrawable().apply {
                                        shape = GradientDrawable.OVAL
                                        setColor(pagePanel)
                                    }
                                }
                                avatar.addView(photo, FrameLayout.LayoutParams(-1, -1))
                            }
                        }
                    }
                }
            }
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            setPadding(dp(3), dp(4), dp(3), dp(8))
            setBackgroundColor(pageBg)
        }
        val messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Keep the conversation anchored to the bottom when there are only a few messages,
            // like Telegram. Older messages remain above and new messages appear at the bottom.
            gravity = Gravity.BOTTOM
            setPadding(dp(3), dp(8), dp(3), dp(8))
        }
        scroll.addView(messages, ViewGroup.LayoutParams(-1, -2))
        // Normal layout: fixed header at top, messages in the middle, composer at bottom.
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(5), dp(5), dp(5))
            background = rounded(pagePanel)
        }
        val selectedPhotoPreview = ImageView(this).apply {
            visibility = if (pendingChatImageFile?.exists() == true) View.VISIBLE else View.GONE
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(pagePanel)
            pendingChatImageFile?.let { file ->
                try { setImageBitmap(BitmapFactory.decodeFile(file.absolutePath)) } catch (_: Exception) {}
            }
            contentDescription = "Selected photo ready to send"
        }
        val input = EditText(this).apply {
            hint = "Message"
            textSize = 15f
            setTextColor(pageText)
            setHintTextColor(pageMuted)
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
            }
            setPadding(dp(5), dp(7), dp(5), dp(7))
            minLines = 1
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        val photo = TextView(this).apply {
            text = "▧"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(teal)
            contentDescription = "Send photo"
            setOnClickListener {
                pendingChatImageConversationId = currentConversationId
                chatImagePicker.launch(arrayOf("image/*"))
            }
        }
        val send = TextView(this).apply {
            text = "➤"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(teal)
            }
            setOnClickListener {
                val body = input.text.toString().trim()
                val user = auth.currentUser
                val conversationId = currentConversationId
                val photoToSend = pendingChatImageFile
                val otherMemberId = if (!currentConversationIsGroup && conversationId != "main")
                    conversationId.split("_").firstOrNull { it != user?.uid } else null
                if (otherMemberId != null && getSharedPreferences("member_actions", MODE_PRIVATE)
                        .getBoolean("blocked_$otherMemberId", false)) {
                    toast("Unblock this member before sending a message.")
                    return@setOnClickListener
                }
                if (user == null || (body.isEmpty() && photoToSend == null)) return@setOnClickListener
                if (photoToSend != null) {
                    pendingChatImageFile = null
                    selectedPhotoPreview.visibility = View.GONE
                    photoToSend.let { uploadChatImage(it, conversationId) }
                }
                if (body.isNotEmpty()) {
                    val collection = if (conversationId == "main")
                        db.collection("groupChats").document("main").collection("messages")
                    else db.collection("chats").document(conversationId).collection("messages")
                    collection.add(mapOf(
                        "text" to body,
                        "senderId" to user.uid,
                        "senderName" to (user.displayName ?: user.email ?: "Member"),
                        "timestamp" to FieldValue.serverTimestamp()
                    )).addOnSuccessListener {
                        input.text.clear()
                        if (conversationId != "main") updateConversationSummary(conversationId, body)
                        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                    }.addOnFailureListener { toast(it.message ?: "Send failed.") }
                }
            }
        }
        val emojiPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(pagePanel)
            visibility = View.GONE
        }
        val emojiButton = TextView(this).apply {
            text = "😊"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(pageText)
            contentDescription = "Choose emoji"
            setOnClickListener {
                val showEmoji = emojiPanel.visibility != View.VISIBLE
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                if (showEmoji) {
                    emojiPanel.visibility = View.VISIBLE
                    text = "⌨"
                    contentDescription = "Show keyboard"
                    imm.hideSoftInputFromWindow(input.windowToken, 0)
                } else {
                    emojiPanel.visibility = View.GONE
                    text = "😊"
                    contentDescription = "Choose emoji"
                    input.requestFocus()
                    imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        // Composer order: attachment, photo preview (when selected), emoji/keyboard toggle,
        // text field, and always-visible send.
        if (pendingChatImageFile?.exists() == true) {
            composer.addView(selectedPhotoPreview, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                rightMargin = dp(5)
            })
        }
        composer.addView(photo, LinearLayout.LayoutParams(dp(36), dp(44)))
        composer.addView(emojiButton, LinearLayout.LayoutParams(dp(40), dp(44)))
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        composer.addView(send, LinearLayout.LayoutParams(dp(42), dp(42)))
        appPageContainer.addView(composer, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(12)
            rightMargin = dp(12)
            topMargin = dp(5)
            bottomMargin = dp(5)
        })
        // Compact emoji picker: only useful, working categories are shown.
        val emojiCategories = linkedMapOf(
            "Recent" to arrayOf("😀", "😂", "😍", "🥰", "😘", "👍", "🙏", "❤️", "🔥", "🎉"),
            "Smileys" to arrayOf("😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇", "🙂", "😉", "😍", "🥰", "😘", "😎", "🤩", "🤔", "🤗", "😴", "😭", "😡"),
            "People" to arrayOf("👍", "👎", "👏", "🙏", "💪", "🤝", "🙌", "👌", "✌️", "🤟", "👋", "🫶"),
            "Nature" to arrayOf("🐶", "🐱", "🐻", "🦁", "🐸", "🌹", "🌻", "🌿", "🌴", "🌈", "☀️", "🔥"),
            "Food" to arrayOf("🍎", "🍕", "🍔", "🍟", "🍗", "🍩", "🍰", "☕", "🥤", "🍻"),
            "Activity" to arrayOf("⚽", "🏀", "🏍️", "🚴", "🏆", "🎯", "🎮", "🎉", "🎂"),
            "Travel" to arrayOf("🚗", "🏍️", "🚕", "🚌", "✈️", "🚀", "🏕️", "🏖️", "🏠"),
            "Symbols" to arrayOf("❤️", "💕", "💯", "✅", "❌", "⭐", "⚡", "💬", "📌", "🔔")
        )
        var selectedEmojiCategory = "Smileys"
        val categoryBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val categoryButtons = mutableMapOf<String, TextView>()
        lateinit var populateEmojiGrid: () -> Unit
        emojiCategories.keys.forEach { category ->
            val symbol = when (category) {
                "Recent" -> "◷"; "Smileys" -> "☺"; "People" -> "♟"; "Nature" -> "❀"
                "Food" -> "♨"; "Activity" -> "⚽"; "Travel" -> "🚗"; else -> "♥"
            }
            val button = TextView(this).apply {
                text = symbol
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(if (category == selectedEmojiCategory) teal else pageMuted)
                contentDescription = category
                setOnClickListener {
                    selectedEmojiCategory = category
                    categoryButtons.forEach { (name, view) -> view.setTextColor(if (name == category) teal else pageMuted) }
                    populateEmojiGrid()
                }
            }
            categoryButtons[category] = button
            categoryBar.addView(button, LinearLayout.LayoutParams(0, dp(38), 1f))
        }
        emojiPanel.addView(categoryBar)
        val emojiGrid = android.widget.GridLayout(this).apply {
            columnCount = 8
            alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
        }
        populateEmojiGrid = {
            emojiGrid.removeAllViews()
            emojiCategories[selectedEmojiCategory].orEmpty().forEach { emoji ->
                val cell = TextView(this).apply {
                    text = emoji
                    textSize = 25f
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        val start = input.selectionStart.coerceAtLeast(0)
                        input.text.insert(start.coerceAtMost(input.text.length), emoji)
                        input.setSelection((start + emoji.length).coerceAtMost(input.text.length))
                    }
                }
                emojiGrid.addView(cell, android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(43)
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                })
            }
        }
        populateEmojiGrid()
        val emojiGridScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(emojiGrid)
        }
        emojiPanel.addView(emojiGridScroll, LinearLayout.LayoutParams(-1, dp(150)))
        appPageContainer.addView(emojiPanel, LinearLayout.LayoutParams(-1, dp(220)).apply {
            leftMargin = dp(12)
            rightMargin = dp(12)
            bottomMargin = dp(6)
        })
        ViewCompat.requestApplyInsets(appPageContainer)

        val collection = if (currentConversationId == "main")
            db.collection("groupChats").document("main").collection("messages")
        else db.collection("chats").document(currentConversationId).collection("messages")
        messagesListener = collection.orderBy("timestamp", Query.Direction.ASCENDING).limit(150)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    toast(error.message ?: "Unable to load messages.")
                    return@addSnapshotListener
                }
                messages.removeAllViews()
                snap?.documents?.forEach { doc ->
                    val sender = doc.getString("senderName") ?: "Member"
                    val senderId = doc.getString("senderId")
                    val body = doc.getString("text") ?: ""
                    val mine = senderId == auth.currentUser?.uid
                    if (!mine && !senderId.isNullOrBlank() &&
                        getSharedPreferences("member_actions", MODE_PRIVATE)
                            .getBoolean("blocked_$senderId", false)) {
                        return@forEach
                    }

                    val line = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = if (mine) Gravity.END else Gravity.START
                        setPadding(dp(2), dp(2), dp(2), dp(2))
                    }
                    val bubble = CappedBubbleLayout(
                        this, (resources.displayMetrics.widthPixels * 0.76f).toInt()
                    ).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(13), dp(9), dp(13), dp(8))
                        val hasImageOnly = !doc.getString("imageUrl").isNullOrBlank() && body.isBlank()
                        background = if (hasImageOnly) {
                            android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                        } else {
                            GradientDrawable().apply {
                                setColor(if (mine) teal else pagePanel)
                                cornerRadius = dp(18).toFloat()
                            }
                        }
                        if (hasImageOnly) setPadding(0, 0, 0, 0)
                    }
                    if (currentConversationId == "main" && !mine) {
                        bubble.addView(TextView(this).apply {
                            text = sender
                            textSize = 11f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(teal)
                        })
                    }
                    val isDeleted = doc.getBoolean("deleted") == true
                    if (isDeleted) {
                        bubble.addView(TextView(this).apply {
                            text = "This message was deleted"
                            textSize = 13f
                            setTypeface(null, Typeface.ITALIC)
                            setTextColor(if (mine) Color.WHITE else pageMuted)
                        })
                    } else if (body.isNotBlank()) {
                        bubble.addView(TextView(this).apply {
                            text = body + if (doc.getBoolean("edited") == true) "  (edited)" else ""
                            textSize = 15f
                            setTextColor(if (mine) Color.WHITE else pageText)
                        })
                    }
                    val imageUrl = if (isDeleted) null else doc.getString("imageUrl")
                    if (!imageUrl.isNullOrBlank()) {
                        val messagePhoto = ImageView(this).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            adjustViewBounds = true
                            maxHeight = dp(240)
                            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                        }
                        bubble.addView(messagePhoto, LinearLayout.LayoutParams(dp(220), dp(220)).apply {
                            topMargin = if (body.isNotBlank()) dp(6) else 0
                        })
                        loadProfileImage(imageUrl) { bitmap ->
                            if (bitmap != null && !isFinishing) {
                                messagePhoto.setImageBitmap(bitmap)
                                messagePhoto.setOnClickListener { showFullImageDialog(bitmap) }
                            }
                        }
                    }
                    val time = doc.getTimestamp("timestamp")?.toDate()?.let {
                        java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(it)
                    } ?: ""
                    if (time.isNotBlank()) {
                        bubble.addView(TextView(this).apply {
                            text = time
                            textSize = 9f
                            gravity = Gravity.END
                            setTextColor(if (mine) Color.WHITE else pageMuted)
                        })
                    }
                    if (!mine) {
                        val avatarHolder = FrameLayout(this)
                        avatarHolder.addView(createAvatar(sender, null, 28))
                        line.addView(avatarHolder, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                            rightMargin = dp(6)
                        })
                        if (!senderId.isNullOrBlank()) {
                            db.collection("members").document(senderId).get()
                                .addOnSuccessListener { profile ->
                                    val photoUrl = profile.getString("photoUrl")
                                    if (!photoUrl.isNullOrBlank() && !isFinishing) {
                                        avatarHolder.removeAllViews()
                                        avatarHolder.addView(
                                            createAvatar(sender, photoUrl, 28),
                                            FrameLayout.LayoutParams(-1, -1)
                                        )
                                    }
                                }
                        }
                    }
                    if (mine && !isDeleted) {
                        // Attach the long-press handler to the bubble AND its child views.
                        // Android dispatches long-press to the deepest touch target, so a
                        // listener on the parent alone can be swallowed by TextView/ImageView.
                        val ownMessageLongPress = View.OnLongClickListener {
                            showOwnMessageActions(doc.reference, body, isDeleted)
                            true
                        }
                        fun attachLongPress(view: View) {
                            view.isLongClickable = true
                            view.setOnLongClickListener(ownMessageLongPress)
                            if (view is ViewGroup) {
                                for (index in 0 until view.childCount) attachLongPress(view.getChildAt(index))
                            }
                        }
                        attachLongPress(bubble)
                        attachLongPress(line)
                        bubble.contentDescription = "Your message. Hold to edit or delete."
                    }
                    line.addView(bubble, LinearLayout.LayoutParams(-2, -2).apply {
                        gravity = Gravity.BOTTOM
                    })
                    if (mine && !isDeleted) {
                        // Re-attach after the bubble is inside its row so every visible child receives the hold gesture.
                        val hold = View.OnLongClickListener { showOwnMessageActions(doc.reference, body, false); true }
                        fun attachHold(view: View) {
                            view.isLongClickable = true
                            view.setOnLongClickListener(hold)
                            if (view is ViewGroup) for (index in 0 until view.childCount) attachHold(view.getChildAt(index))
                        }
                        attachHold(line)
                    }
                    messages.addView(line, LinearLayout.LayoutParams(-1, -2).apply {
                        bottomMargin = dp(5)
                    })
                }
                scroll.post {
                    scroll.requestLayout()
                    scroll.post { scroll.scrollTo(0, messages.bottom.coerceAtLeast(messages.height)) }
                }
            }
    }

    private fun showNewChat() {
        setupAppPage()
        addTopBar("New Chat")
        addSectionTitle("SELECT A MEMBER")
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        membersListener = db.collection("members").addSnapshotListener { snap, error ->
            if (error != null) { toast(error.message ?: "Unable to load members."); return@addSnapshotListener }
            list.removeAllViews()
            snap?.documents?.filter { it.id != auth.currentUser?.uid }?.forEach { doc ->
                val name = doc.getString("displayName") ?: "Member"
                addConversationRow(list, name, "Start a private conversation", "PRIVATE", action = {
                    openPrivateChat(doc.id, name)
                }, photoUrl = doc.getString("photoUrl") ?: doc.getString("photoURL")
                    ?: if (doc.id == auth.currentUser?.uid) auth.currentUser?.photoUrl?.toString() else null, memberId = doc.id)
            }
        }
        addButton("+  NEW GROUP CHAT") { showNewGroupChat() }
        addButton("BACK") { goBack() }
    }

    private fun showNewGroupChat() {
        membersListener?.remove()
        setupAppPage()
        addTopBar("New Group Chat")
        addSectionTitle("GROUP NAME")
        val groupName = EditText(this).apply {
            hint = "Enter group name"
            setTextColor(pageText)
            setHintTextColor(pageMuted)
            background = rounded(pagePanel)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(groupName, LinearLayout.LayoutParams(-1, dp(52)))
        addSectionTitle("SELECT MEMBERS")
        val selected = linkedSetOf<String>()
        val checks = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(checks, LinearLayout.LayoutParams(-1, 0, 1f))
        membersListener = db.collection("members").addSnapshotListener { snap, error ->
            if (error != null) { toast(error.message ?: "Unable to load members."); return@addSnapshotListener }
            checks.removeAllViews()
            snap?.documents?.filter { it.id != auth.currentUser?.uid }?.forEach { doc ->
                val name = doc.getString("displayName") ?: "Member"
                val check = CheckBox(this).apply {
                    text = name
                    setTextColor(pageText)
                    isChecked = doc.id in selected
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selected.add(doc.id) else selected.remove(doc.id)
                    }
                }
                checks.addView(check)
            }
        }
        addButton("CREATE GROUP") {
            val me = auth.currentUser?.uid
            val title = groupName.text.toString().trim()
            if (me == null) { toast("Please sign in again."); return@addButton }
            if (title.isBlank()) { toast("Enter a group name."); return@addButton }
            if (selected.isEmpty()) { toast("Select at least one member."); return@addButton }
            val id = "group_" + db.collection("chats").document().id
            val memberIds = (selected + me).toList()
            db.collection("chats").document(id).set(mapOf(
                "type" to "GROUP", "title" to title, "members" to memberIds,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )).addOnSuccessListener { openConversation(id, title, true) }
                .addOnFailureListener { toast(it.message ?: "Unable to create group chat.") }
        }
        addButton("BACK") { showNewChat() }
    }

    private fun showMembers() {
        messagesListener?.remove(); membersListener?.remove()
        setupAppPage(); addTopBar("Members"); addSectionTitle("ALL MEMBERS")
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        membersListener = db.collection("members").addSnapshotListener { snap, error ->
            if (error != null) { toast(error.message ?: "Unable to load members."); return@addSnapshotListener }
            list.removeAllViews()
            snap?.documents?.forEach { doc ->
                val name = doc.getString("displayName") ?: doc.getString("name") ?: "Member"
                val online = doc.getBoolean("isOnline") == true
                val photoUrl = doc.getString("photoUrl")
                    ?: doc.getString("photoURL")
                    ?: if (doc.id == auth.currentUser?.uid) auth.currentUser?.photoUrl?.toString() else null
                val blocked = getSharedPreferences("member_actions", MODE_PRIVATE)
                    .getBoolean("blocked_${doc.id}", false)
                val status = when {
                    blocked -> "Blocked on this device"
                    online -> "Online"
                    else -> "Offline"
                }
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(11), dp(12), dp(11))
                    background = rounded(pagePanel)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        getSharedPreferences("tracking", MODE_PRIVATE).edit()
                            .putString("selected_member_id", doc.id).apply()
                        navigateTo("TRACK")
                    }
                }
                card.addView(createAvatar(name, photoUrl, 54, teal), LinearLayout.LayoutParams(dp(54), dp(54)))
                val labels = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                }
                labels.addView(TextView(this).apply {
                    text = name; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(pageText)
                })
                labels.addView(TextView(this).apply {
                    text = "📍 Loading last saved address…"; textSize = 12f; setTextColor(pageMuted)
                    maxLines = 2
                    tag = "member_address_${doc.id}"
                })
                labels.addView(TextView(this).apply {
                    text = status; textSize = 11f
                    setTextColor(if (online) teal else pageMuted)
                    setPadding(0, dp(3), 0, 0)
                })
                card.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })

                val storedAddress = listOf("lastKnownAddress", "address", "locationAddress")
                    .firstNotNullOfOrNull { key -> doc.getString(key)?.trim()?.takeIf { it.isNotEmpty() } }
                val lat = doc.getDouble("latitude")
                val lon = doc.getDouble("longitude")
                val addressView = labels.getChildAt(1) as TextView
                if (storedAddress != null) {
                    addressView.text = "📍 $storedAddress"
                } else if (lat != null && lon != null) {
                    Thread {
                        val resolved = reverseGeocode(lat, lon)
                        runOnUiThread {
                            if (addressView.isAttachedToWindow) {
                                addressView.text = "📍 " + resolved.ifBlank { "Address unavailable" }
                            }
                        }
                    }.start()
                } else {
                    addressView.text = "📍 No saved location yet"
                }
            }
            if (snap?.documents.isNullOrEmpty()) {
                list.addView(TextView(this).apply {
                    text = "No club members found yet."; textSize = 14f; setTextColor(pageMuted)
                    setPadding(0, dp(12), 0, dp(12))
                })
            }
        }
        addBottomNavigation()
    }

    private fun showTrack() {
        messagesListener?.remove()
        membersListener?.remove()
        setupAppPage()
        addTopBar("Track")

        val selectedId = getSharedPreferences("tracking", MODE_PRIVATE)
            .getString("selected_member_id", null)
        val profileHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(pagePanel)
        }
        val avatarHolder = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(62), dp(62))
        }
        profileHeader.addView(avatarHolder)
        val memberInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val memberName = TextView(this).apply {
            text = "Choose a member from Members"; textSize = 17f
            setTypeface(null, Typeface.BOLD); setTextColor(pageText)
        }
        val address = TextView(this).apply {
            text = "Select a member to see their saved location."; textSize = 13f
            setTextColor(pageMuted); setPadding(0, dp(4), 0, 0); maxLines = 3
        }
        val locationStatus = TextView(this).apply {
            text = ""; textSize = 11f; setTextColor(teal); setPadding(0, dp(4), 0, 0)
        }
        memberInfo.addView(memberName)
        memberInfo.addView(address)
        memberInfo.addView(locationStatus)
        profileHeader.addView(memberInfo, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(profileHeader, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val locationToggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            background = rounded(pagePanel)
        }
        val locationToggleInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        locationToggleInfo.addView(TextView(this).apply {
            text = "Share my live location"; textSize = 14f
            setTypeface(null, Typeface.BOLD); setTextColor(pageText)
        })
        locationToggleInfo.addView(TextView(this).apply {
            text = "Updates about every 15 seconds"; textSize = 12f; setTextColor(pageMuted)
        })
        locationToggleRow.addView(locationToggleInfo, LinearLayout.LayoutParams(0, -2, 1f))
        val locationSwitch = Switch(this).apply {
            text = ""
            isChecked = getSharedPreferences("elbandidos_preferences", MODE_PRIVATE)
                .getBoolean("location_sharing_enabled", true)
            contentDescription = "Share my live location"
        }
        locationToggleRow.addView(locationSwitch)
        locationSwitch.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences("elbandidos_preferences", MODE_PRIVATE).edit()
                .putBoolean("location_sharing_enabled", checked).apply()
            if (checked) startLocationSharing() else stopLocationSharing()
        }
        root.addView(locationToggleRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val map = android.webkit.WebView(this).apply {
            setBackgroundColor(pagePanel)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(-1, dp(440))
        }
        root.addView(map, LinearLayout.LayoutParams(-1, dp(440)).apply { bottomMargin = dp(8) })
        map.loadDataWithBaseURL("https://www.openstreetmap.org/",
            "<html><body style='margin:0;background:#e8eeee;font-family:sans-serif;display:flex;align-items:center;justify-content:center;color:#526568'><div>Select a member from the Members tab to show their location on the map.</div></body></html>",
            "text/html", "UTF-8", null)

        if (selectedId.isNullOrBlank()) {
            root.addView(TextView(this).apply {
                text = "Open Members and tap a member to track them."; textSize = 13f
                setTextColor(pageMuted); setPadding(dp(4), dp(4), dp(4), dp(8))
            })
        } else {
            membersListener = db.collection("members").document(selectedId).addSnapshotListener { doc, error ->
                if (error != null) { address.text = "Unable to load member location."; return@addSnapshotListener }
                if (doc == null || !doc.exists()) {
                    memberName.text = "Member not found"
                    address.text = "This member profile is no longer available."
                    return@addSnapshotListener
                }
                val name = doc.getString("displayName") ?: doc.getString("name") ?: "Member"
                val photoUrl = doc.getString("photoUrl") ?: doc.getString("photoURL")
                memberName.text = name
                avatarHolder.removeAllViews()
                avatarHolder.addView(createAvatar(name, photoUrl, 62, teal))
                val sharing = doc.getBoolean("isSharingLocation") == true
                val lat = doc.getDouble("latitude")
                val lon = doc.getDouble("longitude")
                val savedAt = doc.getTimestamp("locationUpdatedAt")?.toDate()
                val ageMs = savedAt?.let { System.currentTimeMillis() - it.time }
                val live = sharing && ageMs != null && ageMs in 0..120_000L
                locationStatus.text = if (live) "● LIVE LOCATION" else "● LAST SAVED LOCATION"
                locationStatus.setTextColor(if (live) teal else pageMuted)

                val storedAddress = listOf("lastKnownAddress", "address", "locationAddress")
                    .firstNotNullOfOrNull { key -> doc.getString(key)?.trim()?.takeIf { it.isNotEmpty() } }
                if (lat == null || lon == null) {
                    address.text = storedAddress ?: "No saved location available yet."
                    map.loadDataWithBaseURL("https://www.openstreetmap.org/",
                        "<html><body style='margin:0;padding:24px;font-family:sans-serif;color:#526568'>No saved coordinates for this member yet.</body></html>",
                        "text/html", "UTF-8", null)
                } else {
                    address.text = storedAddress ?: "Finding address…"
                    val safePhotoJson = org.json.JSONObject.quote(photoUrl ?: "")
                    val safeNameJson = org.json.JSONObject.quote(name)
                    val html = """
                        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
                        <link href="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.css" rel="stylesheet" />
                        <style>html,body,#map{height:100%;width:100%;margin:0;padding:0;overflow:hidden}.avatar-pin{width:54px;height:54px;box-sizing:border-box;border:3px solid white;border-radius:50%;box-shadow:0 2px 8px #333;object-fit:cover;background:#168b88;display:flex;align-items:center;justify-content:center;color:white;font-weight:bold;font-family:sans-serif;overflow:hidden}.avatar-photo{width:100%;height:100%;object-fit:cover;border:0;border-radius:50%}.maplibregl-popup-content{font-family:sans-serif;font-size:14px}.maplibregl-ctrl-attrib{font-size:10px}</style>
                        </head><body><div id="map"></div>
                        <script src="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.js"></script>
                        <script>
                        const lat=$lat, lon=$lon;
                        const map=new maplibregl.Map({container:'map',style:'https://tiles.openfreemap.org/styles/liberty',center:[lon,lat],zoom:16,attributionControl:true});
                        const photo=$safePhotoJson;
                        const marker=document.createElement('div'); marker.className='avatar-pin';
                        if(photo){const img=document.createElement('img');img.className='avatar-photo';img.src=photo;img.alt='Member profile';marker.appendChild(img);}else{marker.textContent='EB';}
                        new maplibregl.Marker({element:marker,anchor:'center'}).setLngLat([lon,lat]).setPopup(new maplibregl.Popup({offset:28}).setText($safeNameJson)).addTo(map);
                        map.addControl(new maplibregl.NavigationControl({showCompass:false}),'top-right');
                        </script></body></html>
                    """.trimIndent()
                    map.loadDataWithBaseURL("https://appassets.androidplatform.net/", html, "text/html", "UTF-8", null)
                    if (storedAddress == null) {
                        Thread {
                            val resolved = reverseGeocode(lat, lon)
                            runOnUiThread {
                                if (address.isAttachedToWindow) {
                                    address.text = resolved.ifBlank { "Address unavailable; map shows the saved coordinates." }
                                }
                            }
                        }.start()
                    }
                }
            }
        }
        addBottomNavigation()
    }

    private fun reverseGeocode(lat: Double, lon: Double): String {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val latch = java.util.concurrent.CountDownLatch(1)
                var result = ""
                android.location.Geocoder(this, java.util.Locale.getDefault())
                    .getFromLocation(lat, lon, 1) { addresses ->
                        result = formatReadableAddress(addresses.firstOrNull())
                        latch.countDown()
                    }
                latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
                result
            } else {
                @Suppress("DEPRECATION")
                val addresses = android.location.Geocoder(this, java.util.Locale.getDefault())
                    .getFromLocation(lat, lon, 1)
                formatReadableAddress(addresses?.firstOrNull())
            }
        } catch (_: Exception) { "" }
    }

    private fun formatReadableAddress(address: android.location.Address?): String {
        if (address == null) return ""
        val parts = listOfNotNull(
            address.subThoroughfare,
            address.thoroughfare,
            address.subLocality,
            address.locality,
            address.subAdminArea,
            address.adminArea,
            address.countryName
        ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return parts.joinToString(", ")
    }

    private fun startLocationSharing() {
        val prefs = getSharedPreferences("elbandidos_preferences", MODE_PRIVATE)
        if (!prefs.getBoolean("location_sharing_enabled", true)) return
        val user = auth.currentUser ?: return
        val fine = android.Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = android.Manifest.permission.ACCESS_COARSE_LOCATION
        if (checkSelfPermission(fine) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(coarse) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(fine, coarse), 7001)
            return
        }
        try {
            locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
            val manager = locationManager ?: return
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: android.location.Location) {
                    val currentUser = auth.currentUser ?: return
                    db.collection("members").document(currentUser.uid).set(mapOf(
                        "latitude" to location.latitude,
                        "longitude" to location.longitude,
                        "locationUpdatedAt" to FieldValue.serverTimestamp(),
                        "isSharingLocation" to true
                    ), com.google.firebase.firestore.SetOptions.merge())
                }
            }
            locationListener?.let { try { manager.removeUpdates(it) } catch (_: Exception) {} }
            locationListener = listener
            var requested = false
            for (provider in listOf(android.location.LocationManager.GPS_PROVIDER,
                                    android.location.LocationManager.NETWORK_PROVIDER)) {
                try {
                    if (manager.isProviderEnabled(provider)) {
                        // 15-second interval; 0 metres avoids suppressing updates while standing still.
                        manager.requestLocationUpdates(provider, 15000L, 0f, listener, android.os.Looper.getMainLooper())
                        requested = true
                    }
                } catch (_: IllegalArgumentException) { }
            }
            if (!requested) {
                locationListener = null
                toast("Turn on Location in Android settings, then try again.")
                return
            }
            db.collection("members").document(user.uid).set(
                mapOf("isSharingLocation" to true), com.google.firebase.firestore.SetOptions.merge())
            locationSharing = true
        } catch (e: SecurityException) {
            toast("Allow location permission to share your location.")
        } catch (e: Exception) {
            toast(e.message ?: "Unable to start location sharing.")
        }
    }

    private fun stopLocationSharing() {
        stopLocationUpdates()
        auth.currentUser?.uid?.let { uid ->
            db.collection("members").document(uid).set(
                mapOf("isSharingLocation" to false), com.google.firebase.firestore.SetOptions.merge())
        }
        locationSharing = false
        toast("Location sharing stopped.")
    }

    private fun stopLocationUpdates() {
        try { locationListener?.let { locationManager?.removeUpdates(it) } } catch (_: Exception) { }
        locationListener = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7001) {
            val enabled = getSharedPreferences("elbandidos_preferences", MODE_PRIVATE)
                .getBoolean("location_sharing_enabled", true)
            if (enabled && grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) startLocationSharing()
            else if (enabled) toast("Location permission was not granted. Location sharing is unavailable.")
        }
    }

    private fun showProfile() {
        messagesListener?.remove()
        membersListener?.remove()
        setupAppPage()
        val user = auth.currentUser
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.elbandidos_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        top.addView(logo, LinearLayout.LayoutParams(dp(34), dp(34)))
        top.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f))
        val menu = TextView(this).apply {
            text = "⋮"
            textSize = 29f
            gravity = Gravity.CENTER
            setTextColor(pageText)
            setOnClickListener { showSettingsMenu(this) }
        }
        top.addView(menu, LinearLayout.LayoutParams(dp(42), dp(42)))
        root.addView(top)

        val profileCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(16))
        }
        val avatar = createAvatar(user?.displayName ?: "Member", null, 136)
        profileCard.addView(avatar, LinearLayout.LayoutParams(dp(136), dp(136)).apply {
            bottomMargin = dp(10)
        })
        val name = TextView(this).apply {
            text = user?.displayName ?: "Member"
            textSize = 23f
            setTypeface(null, Typeface.BOLD)
            setTextColor(pageText)
            gravity = Gravity.CENTER
        }
        profileCard.addView(name)
        profileCard.addView(TextView(this).apply {
            text = "online"
            setTextColor(teal)
            textSize = 14f
            setTextColor(pageMuted)
            gravity = Gravity.CENTER
        })
        root.addView(profileCard, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
            bottomMargin = dp(12)
        })

        user?.uid?.let { uid ->
            db.collection("members").document(uid).get().addOnSuccessListener { profile ->
                val url = profile.getString("photoUrl") ?: user.photoUrl?.toString()
                if (!url.isNullOrBlank()) {
                    loadProfileImage(url) { bitmap ->
                        if (bitmap != null && !isFinishing && avatar.childCount > 0) {
                            avatar.removeAllViews()
                            val photo = ImageView(this).apply {
                                setImageBitmap(bitmap)
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                clipToOutline = true
                                background = GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(pagePanel)
                                }
                            }
                            avatar.addView(photo, FrameLayout.LayoutParams(-1, -1))
                        }
                    }
                }
            }
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun profileAction(icon: String, label: String, action: () -> Unit) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(pagePanel)
                setPadding(dp(5), dp(10), dp(5), dp(10))
                setOnClickListener { action() }
            }
            item.addView(TextView(this).apply {
                text = icon
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(pageText)
            })
            item.addView(TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setTextColor(pageText)
            })
            actions.addView(item, LinearLayout.LayoutParams(0, dp(82), 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            })
        }
        profileAction("▣", "Set Photo") {
            profileImagePicker.launch(arrayOf("image/*"))
        }
        profileAction("✎", "Edit Info") {
            val dialogBg = if (lightTheme) Color.rgb(248, 250, 250) else Color.rgb(13, 48, 53)
            val titleColor = if (lightTheme) Color.rgb(24, 42, 44) else Color.rgb(232, 245, 244)
            val secondaryColor = if (lightTheme) Color.rgb(94, 111, 117) else Color.rgb(151, 184, 183)

            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = dialogShape(dialogBg, teal)
                setPadding(dp(22), dp(22), dp(22), dp(18))
            }

            content.addView(TextView(this).apply {
                text = "Edit Profile Name"
                textSize = 21f
                setTypeface(null, Typeface.BOLD)
                setTextColor(titleColor)
            })

            content.addView(TextView(this).apply {
                text = "Choose the name other members will see."
                textSize = 13f
                setTextColor(secondaryColor)
                setPadding(0, dp(6), 0, dp(22))
            })

            content.addView(TextView(this).apply {
                text = "DISPLAY NAME"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(teal)
                setPadding(0, 0, 0, dp(8))
            })

            val field = EditText(this).apply {
                setText(user?.displayName ?: "")
                hint = "Enter your display name"
                setSingleLine(true)
                textSize = 16f
                setTextColor(titleColor)
                setHintTextColor(secondaryColor)
                background = dialogShape(
                    if (lightTheme) Color.WHITE else Color.rgb(16, 53, 58),
                    if (lightTheme) Color.rgb(190, 207, 204) else Color.rgb(40, 99, 106)
                )
                setPadding(dp(15), dp(13), dp(15), dp(13))
            }
            content.addView(field, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(24)
            })

            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }

            val cancelButton = TextView(this).apply {
                text = "CANCEL"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(titleColor)
                gravity = Gravity.CENTER
                background = dialogShape(
                    if (lightTheme) Color.rgb(232, 240, 239) else Color.rgb(25, 69, 73)
                )
                setPadding(dp(19), dp(12), dp(19), dp(12))
                isClickable = true
            }

            val saveButton = TextView(this).apply {
                text = "SAVE"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = dialogShape(teal)
                setPadding(dp(24), dp(12), dp(24), dp(12))
                isClickable = true
            }

            buttons.addView(cancelButton, LinearLayout.LayoutParams(-2, -2).apply {
                marginEnd = dp(10)
            })
            buttons.addView(saveButton)
            content.addView(buttons)

            val editDialog = android.app.AlertDialog.Builder(this)
                .setView(content)
                .create()

            cancelButton.setOnClickListener { editDialog.dismiss() }
            saveButton.setOnClickListener {
                val newName = field.text.toString().trim()
                if (newName.isBlank()) {
                    toast("Enter a display name.")
                } else {
                    val currentUser = auth.currentUser
                    if (currentUser == null) {
                        toast("Please sign in again.")
                    } else {
                        saveButton.isEnabled = false
                        saveButton.alpha = 0.6f
                        val request = UserProfileChangeRequest.Builder()
                            .setDisplayName(newName).build()
                        currentUser.updateProfile(request).addOnSuccessListener {
                            db.collection("members").document(currentUser.uid).set(
                                mapOf(
                                    "displayName" to newName,
                                    "updatedAt" to FieldValue.serverTimestamp()
                                ), com.google.firebase.firestore.SetOptions.merge()
                            ).addOnSuccessListener {
                                editDialog.dismiss()
                                toast("Name updated.")
                                showProfile()
                            }.addOnFailureListener {
                                saveButton.isEnabled = true
                                saveButton.alpha = 1f
                                toast(it.message ?: "Unable to save name.")
                            }
                        }.addOnFailureListener {
                            saveButton.isEnabled = true
                            saveButton.alpha = 1f
                            toast(it.message ?: "Unable to update name.")
                        }
                    }
                }
            }

            editDialog.show()
            styleDialog(editDialog)
            editDialog.window?.apply {
                setBackgroundDrawable(dialogShape(dialogBg, teal))
                setLayout(
                    resources.displayMetrics.widthPixels - dp(40),
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
            }
            field.requestFocus()
        }
        profileAction("⚙", "Settings") { navigateTo("SETTINGS") }
        root.addView(actions, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(16)
        })

        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(pagePanel)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        infoCard.addView(TextView(this).apply {
            text = user?.email ?: "No email address"
            textSize = 16f
            setTextColor(pageText)
        })
        infoCard.addView(TextView(this).apply {
            text = "Email"
            textSize = 12f
            setTextColor(pageMuted)
            setPadding(0, dp(4), 0, 0)
        })
        root.addView(infoCard, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(14)
        })

        if (selectedProfileImage != null) {
            try {
                contentResolver.openInputStream(selectedProfileImage!!)?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    if (bitmap != null) {
                        avatar.removeAllViews()
                        val preview = ImageView(this).apply {
                            setImageBitmap(bitmap)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            clipToOutline = true
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(pagePanel)
                            }
                        }
                        avatar.addView(preview, FrameLayout.LayoutParams(-1, -1))
                    }
                }
            } catch (_: Exception) { }
            addButton("SAVE PROFILE", true) {
                val currentUser = auth.currentUser ?: return@addButton
                val photo = selectedProfileImage ?: return@addButton
                uploadProfileImageToCloudinary(
                    imageUri = photo,
                    callback = { uploadedUrl: String?, error: String? ->
                        if (uploadedUrl.isNullOrBlank()) {
                            toast(error ?: "Profile picture upload failed.")
                        } else {
                            val request = UserProfileChangeRequest.Builder()
                                .setPhotoUri(Uri.parse(uploadedUrl)).build()
                            currentUser.updateProfile(request).addOnSuccessListener {
                                db.collection("members").document(currentUser.uid).set(
                                    mapOf(
                                        "photoUrl" to uploadedUrl,
                                        "updatedAt" to FieldValue.serverTimestamp()
                                    ), com.google.firebase.firestore.SetOptions.merge()
                                ).addOnSuccessListener {
                                    selectedProfileImage = null
                                    toast("Profile picture saved.")
                                    showProfile()
                                }.addOnFailureListener { toast(it.message ?: "Unable to save profile picture.") }
                            }.addOnFailureListener { toast(it.message ?: "Unable to update profile picture.") }
                        }
                    }
                )
            }
        }
        addBottomNavigation()
    }

    private fun showFullImageDialog(bitmap: Bitmap) {
        val content = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        val fullImage = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        content.addView(fullImage, FrameLayout.LayoutParams(-1, -1))
        val close = TextView(this).apply {
            text = "✕"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(180, 25, 25, 25))
            }
            setOnClickListener { (tag as? android.app.Dialog)?.dismiss() }
        }
        content.addView(close, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(12)
            rightMargin = dp(12)
        })
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        close.tag = dialog
        dialog.setContentView(content)
        dialog.show()
    }

    private fun loadProfileImage(imageUrl: String, callback: (Bitmap?) -> Unit) {
        Thread {
            val bitmap = try {
                val connection = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000
                    readTimeout = 15000
                    instanceFollowRedirects = true
                    doInput = true
                }
                try {
                    connection.connect()
                    if (connection.responseCode in 200..299) {
                        connection.inputStream.use { BitmapFactory.decodeStream(it) }
                    } else null
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                null
            }
            runOnUiThread { callback(bitmap) }
        }.start()
    }

    private fun uploadProfileImageToCloudinary(
        imageUri: Uri,
        callback: (String?, String?) -> Unit,
        folder: String = "elbandidos_profiles",
        compressForChat: Boolean = false
    ) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val boundary = "----ElBandidos${UUID.randomUUID()}"
                connection = (URL("https://api.cloudinary.com/v1_1/izu92wql/image/upload")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    doInput = true
                    connectTimeout = 30000
                    readTimeout = 30000
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                }

                val output = connection.outputStream
                fun writeTextField(field: String, value: String) {
                    output.write("--$boundary\r\n".toByteArray())
                    output.write("Content-Disposition: form-data; name=\"$field\"\r\n\r\n".toByteArray())
                    output.write(value.toByteArray())
                    output.write("\r\n".toByteArray())
                }

                writeTextField("upload_preset", "boy2yaw")
                writeTextField("folder", folder)

                output.write("--$boundary\r\n".toByteArray())
                output.write(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"profile.jpg\"\r\n".toByteArray()
                )
                output.write("Content-Type: image/jpeg\r\n\r\n".toByteArray())
                if (compressForChat) {
                    val compressed = compressChatPhoto(imageUri)
                    if (compressed.size > 5 * 1024 * 1024) {
                        throw Exception("Photo is still larger than 5 MB after compression. Please choose another photo.")
                    }
                    output.write(compressed)
                } else {
                    contentResolver.openInputStream(imageUri)?.use { input ->
                        input.copyTo(output)
                    } ?: throw Exception("Cannot read selected image.")
                }
                output.write("\r\n--$boundary--\r\n".toByteArray())
                output.flush()
                output.close()

                val status = connection.responseCode
                val responseStream = if (status in 200..299) connection.inputStream else connection.errorStream
                val responseText = responseStream.bufferedReader().use { it.readText() }
                if (status !in 200..299) {
                    val message = try {
                        JSONObject(responseText).optJSONObject("error")?.optString("message")
                    } catch (_: Exception) { null }
                    throw Exception(message?.takeIf { it.isNotBlank() } ?: "Cloudinary upload failed (HTTP $status).")
                }
                val secureUrl = JSONObject(responseText).optString("secure_url")
                if (secureUrl.isBlank()) throw Exception("Cloudinary did not return an image URL.")
                runOnUiThread { callback(secureUrl, null) }
            } catch (e: Exception) {
                runOnUiThread { callback(null, e.message ?: "Profile picture upload failed.") }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun copyChatImageToCache(uri: Uri): File? {
        return try {
            val file = File(cacheDir, "chat_image_${UUID.randomUUID()}.source")
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (!file.exists() || file.length() == 0L) {
                file.delete()
                null
            } else file
        } catch (_: Exception) {
            null
        }
    }

    private fun uploadChatImage(imageFile: File, conversationId: String) {
        val user = auth.currentUser ?: return
        if (conversationId != currentConversationId) {
            imageFile.delete()
            toast("Photo sending cancelled because the conversation changed.")
            return
        }
        toast("Uploading photo…")
        uploadChatFileToCloudinary(imageFile, callback = { imageUrl, error ->
            if (imageUrl == null) {
                toast(error ?: "Photo upload failed.")
                return@uploadChatFileToCloudinary
            }
            val collection = if (conversationId == "main")
                db.collection("groupChats").document("main").collection("messages")
            else db.collection("chats").document(conversationId).collection("messages")
            val otherMemberId = if (conversationId != "main")
                conversationId.split("_").firstOrNull { it != user.uid } else null
            if (otherMemberId != null && getSharedPreferences("member_actions", MODE_PRIVATE)
                    .getBoolean("blocked_$otherMemberId", false)) {
                toast("Unblock this member before sending a photo.")
                return@uploadChatFileToCloudinary
            }
            collection.add(mapOf(
                "text" to "",
                "imageUrl" to imageUrl,
                "senderId" to user.uid,
                "senderName" to (user.displayName ?: user.email ?: "Member"),
                "timestamp" to FieldValue.serverTimestamp()
            )).addOnSuccessListener {
                updateConversationSummary(conversationId, "📷 Photo")
            }.addOnFailureListener { toast(it.message ?: "Unable to send photo.") }
        }, folder = "elbandidos_chat")
    }

    private fun uploadChatFileToCloudinary(
        imageFile: File,
        callback: (String?, String?) -> Unit,
        folder: String
    ) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val boundary = "----ElBandidos${UUID.randomUUID()}"
                connection = (URL("https://api.cloudinary.com/v1_1/izu92wql/image/upload")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    doInput = true
                    connectTimeout = 30000
                    readTimeout = 30000
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                }
                connection.outputStream.use { output ->
                    fun field(name: String, value: String) {
                        output.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                    }
                    field("upload_preset", "boy2yaw")
                    field("folder", folder)
                    output.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"chat_${UUID.randomUUID()}.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                    val compressed = compressChatPhoto(imageFile)
                    if (compressed.size > 5 * 1024 * 1024) throw Exception("Photo is larger than 5 MB after compression. Please choose another photo.")
                    output.write(compressed)
                    output.write("\r\n--$boundary--\r\n".toByteArray())
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val response = stream.bufferedReader().use { it.readText() }
                if (status !in 200..299) {
                    val message = try { JSONObject(response).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                    throw Exception(message?.takeIf { it.isNotBlank() } ?: "Cloudinary upload failed (HTTP $status).")
                }
                val url = JSONObject(response).optString("secure_url")
                if (url.isBlank()) throw Exception("Cloudinary did not return an image URL.")
                runOnUiThread { callback(url, null) }
            } catch (e: Exception) {
                runOnUiThread { callback(null, e.message ?: "Photo upload failed.") }
            } finally {
                connection?.disconnect()
                imageFile.delete()
            }
        }.start()
    }

    private fun compressChatPhoto(imageUri: Uri): ByteArray {
        val tempImage = File.createTempFile("chat_upload_", ".img", cacheDir)
        try {
            contentResolver.openInputStream(imageUri)?.use { input ->
                tempImage.outputStream().use { output -> input.copyTo(output) }
            } ?: throw Exception("Cannot read selected image. Please choose the photo again.")
            if (tempImage.length() <= 0L) throw Exception("Selected image is empty or unavailable.")
            return compressChatPhoto(tempImage)
        } finally {
            tempImage.delete()
        }
    }

    private fun compressChatPhoto(imageFile: File): ByteArray {
        val bitmap: Bitmap = if (android.os.Build.VERSION.SDK_INT >= 28) {
            val source = android.graphics.ImageDecoder.createSource(imageFile)
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                val scale = minOf(1.0, 1600.0 / maxOf(width, height))
                if (scale < 1.0) decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
                decoder.isMutableRequired = true
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw Exception("Unsupported or invalid image file.")
            var sample = 1
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
            BitmapFactory.decodeFile(imageFile.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw Exception("Unable to decode selected image format.")
        }
        try {
            var quality = 82
            var bytes: ByteArray
            do {
                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                bytes = output.toByteArray()
                quality -= 7
            } while (bytes.size > 5 * 1024 * 1024 && quality >= 54)
            return bytes
        } finally { bitmap.recycle() }
    }

    private fun showSettings() {
        setupAppPage()
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = "Settings"
            textSize = 23f
            setTypeface(null, Typeface.BOLD)
            setTextColor(pageText)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(TextView(this).apply {
            text = "⋮"
            textSize = 27f
            setTextColor(pageText)
            gravity = Gravity.CENTER
            setOnClickListener { showSettingsMenu(this) }
        }, LinearLayout.LayoutParams(dp(40), dp(44)))
        root.addView(top)
        addSectionTitle("PREFERENCES")
        addButton(if (lightTheme) "☾  SWITCH TO DARK THEME" else "☀  SWITCH TO LIGHT THEME") {
            lightTheme = !lightTheme
            saveThemePreference()
            showSettings()
        }
        addSectionTitle("MEMBERS")
        addButton("MEMBER LOCATION / TRACKING", false) { navigateTo("TRACK") }
        addSectionTitle("ACCOUNT")
        addButton("EDIT PROFILE", false) { navigateTo("PROFILE") }
        addButton("LOG OUT", false) {
            stopLocationUpdates()
            updatePresence(false)
            auth.signOut()
            pageHistory.clear()
            currentPageKey = "CHAT"
            showLogin()
        }
        addBottomNavigation()
    }

    private fun addSectionTitle(title: String) {
        val tv = TextView(this).apply {
            text = title; textSize = 15f; setTextColor(teal)
            setTypeface(null, Typeface.BOLD); gravity = Gravity.START
            setPadding(0, dp(10), 0, dp(8))
        }
        root.addView(tv)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
