package ru.bluecat.android.xposed.mods.appsettings;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.BlendModeColorFilterCompat;
import androidx.core.graphics.BlendModeCompat;

import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.bluecat.android.xposed.mods.appsettings.ui.LocaleList;
import ru.bluecat.android.xposed.mods.appsettings.ui.PermissionSettings;

public class AppSettingsActivity extends AppCompatActivity {

	private SwitchCompat swtActive;

	private static SharedPreferences prefs;
	private String pkgName;
	private Set<String> settingKeys;
	private Map<String, Object> initialSettings;
	private Map<String, Object> onStartSettings;
	private Set<String> disabledPermissions;
	private boolean allowRevoking;
	private Intent parentIntent;
	private LocaleList localeList;

	/** Called when the activity is first created. */
	@SuppressLint({"SetTextI18n", "DefaultLocale", "WorldReadableFiles"})
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		try {
			//noinspection deprecation
			prefs = getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
		} catch (SecurityException e) {
			Utils.showToast(this, Pair.of(e.getMessage(), 0), null, Toast.LENGTH_LONG);
			finish();
		}
		setContentView(R.layout.app_settings_activity);
		Toolbar toolbar = findViewById(R.id.appToolbar);
		setSupportActionBar(toolbar);
		ActionBar bar = getSupportActionBar();
		if (bar != null) {
			bar.setDisplayShowCustomEnabled(true);
			bar.setDisplayHomeAsUpEnabled(true);
		}
		toolbar.setNavigationOnClickListener(v -> onBackPressed());

		swtActive = new SwitchCompat(this);
		Objects.requireNonNull(getSupportActionBar()).setCustomView(swtActive);
		Intent i = getIntent();
		parentIntent = i;
		ApplicationInfo app;
		try {
			app = getPackageManager().getApplicationInfo(Objects.requireNonNull(
			        i.getStringExtra("package")), 0);
			pkgName = app.packageName;
		} catch (NameNotFoundException e) {
			// Close the dialog gracefully, package might have been uninstalled
			finish();
			return;
		}

		// Display app info
		((TextView) findViewById(R.id.app_label)).setText(app.loadLabel(getPackageManager()));
		((TextView) findViewById(R.id.package_name)).setText(app.packageName);
		((ImageView) findViewById(R.id.app_icon)).setImageDrawable(app.loadIcon(getPackageManager()));

		// Update switch of active/inactive tweaks
		if (prefs.getBoolean(pkgName + Constants.PREF_ACTIVE, false)) {
			swtActive.setChecked(true);
			findViewById(R.id.viewTweaks).setVisibility(View.VISIBLE);
		} else {
			swtActive.setChecked(false);
			findViewById(R.id.viewTweaks).setVisibility(View.GONE);
		}
		// Toggle the visibility of the lower panel when changed
		swtActive.setOnCheckedChangeListener((buttonView, isChecked) ->
				findViewById(R.id.viewTweaks).setVisibility(isChecked ? View.VISIBLE : View.GONE));

		// Update DPI field
		if (prefs.getBoolean(pkgName + Constants.PREF_ACTIVE, false)) {
			((EditText) findViewById(R.id.txtDPI)).setText(String.valueOf(
				prefs.getInt(pkgName + Constants.PREF_DPI, 0)));
		} else {
			((EditText) findViewById(R.id.txtDPI)).setText("0");
		}

		// Update Font Scaling field
		if (prefs.getBoolean(pkgName + Constants.PREF_ACTIVE, false)) {
			((EditText) findViewById(R.id.txtFontScale)).setText(String.valueOf(prefs.getInt(pkgName + Constants.PREF_FONT_SCALE, 100)));
		} else {
			((EditText) findViewById(R.id.txtFontScale)).setText("100");
		}

		// Load and render current screen setting + possible options
		int screen = prefs.getInt(pkgName + Constants.PREF_SCREEN, 0);
		if (screen < 0 || screen >= Constants.swdp.length)
			screen = 0;
		final int selectedScreen = screen;

		Spinner spnScreen = findViewById(R.id.spnScreen);
		List<String> lstScreens = new ArrayList<>(Constants.swdp.length);
		lstScreens.add(getString(R.string.settings_default));
		for (int j = 1; j < Constants.swdp.length; j++)
			lstScreens.add(String.format("%dx%d", Constants.wdp[j], Constants.hdp[j]));
		ArrayAdapter<String> screenAdapter = new ArrayAdapter<>(this,
				android.R.layout.simple_spinner_item, lstScreens);
		screenAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		spnScreen.setAdapter(screenAdapter);
		spnScreen.setSelection(selectedScreen);

		// Update Tablet field
		((CheckBox) findViewById(R.id.chkXlarge)).setChecked(prefs.getBoolean(pkgName + Constants.PREF_XLARGE, false));

		// Update Layout field
		((CheckBox) findViewById(R.id.chkLTR)).setChecked(prefs.getBoolean(pkgName + Constants.PREF_LTR, false));

		// Update Screenshot field
		{
			int screenshot = prefs.getInt(pkgName + Constants.PREF_SCREENSHOT, Constants.PREF_SCREENSHOT_DEFAULT);
			Spinner spnScreenshot = findViewById(R.id.spnScreenshot);
			// Note: the order of these items must match the Common.PREF_SCREENSHOT... constants
			String[] screenshotArray = new String[] {
					getString(R.string.settings_default),
					getString(R.string.settings_allow),
					getString(R.string.settings_prevent)
			};

			List<String> listScreenshot = Arrays.asList(screenshotArray);
			ArrayAdapter<String> screenshotAdapter = new ArrayAdapter<>(this,
					android.R.layout.simple_spinner_item, listScreenshot);
			screenshotAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
			spnScreenshot.setAdapter(screenshotAdapter);
			spnScreenshot.setSelection(screenshot);
		}

		// Update Language and list of possibilities
		localeList = new LocaleList(getString(R.string.settings_default));

		final Spinner spnLanguage = findViewById(R.id.spnLocale);
		ArrayAdapter<String> dataAdapter = new ArrayAdapter<>(this,
				android.R.layout.simple_spinner_item, localeList.getDescriptionList());
		dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		spnLanguage.setAdapter(dataAdapter);
		int selectedLocalePos = localeList.getLocalePos(prefs.getString(pkgName + Constants.PREF_LOCALE, null));
		spnLanguage.setSelection(selectedLocalePos);
		spnLanguage.setLongClickable(true);
		spnLanguage.setOnLongClickListener(arg0 -> {
			int selPos = spnLanguage.getSelectedItemPosition();
			if (selPos > 0)
				Toast.makeText(getApplicationContext(), localeList.getLocale(selPos), Toast.LENGTH_SHORT).show();
			return true;
		});


		// Helper to list all apk folders under /res
		findViewById(R.id.btnListRes).setOnClickListener(v -> {
			AlertDialog.Builder builder = new AlertDialog.Builder(this);

			ScrollView scrollPane = new ScrollView(this);
			TextView txtPane = new TextView(this);
			StringBuilder contents = new StringBuilder();
			JarFile jar = null;
			TreeSet<String> resEntries = new TreeSet<>();
			Matcher m = Pattern.compile("res/(.+)/[^/]+").matcher("");
			try {
				ApplicationInfo app1 = getPackageManager().getApplicationInfo(pkgName, 0);
				jar = new JarFile(app1.publicSourceDir);
				Enumeration<JarEntry> entries = jar.entries();
				while (entries.hasMoreElements()) {
					JarEntry entry = entries.nextElement();
					m.reset(entry.getName());
					if (m.matches())
						resEntries.add(m.group(1));
				}
				if (resEntries.size() == 0)
					resEntries.add(getString(R.string.res_noentries));
				jar.close();
				for (String dir : resEntries) {
					contents.append('\n');
					contents.append(dir);
				}
				contents.deleteCharAt(0);
			} catch (Exception e) {
				contents.append(getString(R.string.res_failedtoload));
				if (jar != null) {
					try {
						jar.close();
					} catch (Exception ignored) { }
				}
			}
			txtPane.setText(contents);
			scrollPane.addView(txtPane);
			builder.setView(scrollPane);
			builder.setTitle(R.string.res_title);
			builder.show();
		});


		// Setup fullscreen settings
		{
			int fullscreen;
			try {
				fullscreen = prefs.getInt(pkgName + Constants.PREF_FULLSCREEN, Constants.FULLSCREEN_DEFAULT);
			} catch (ClassCastException ex) {
				// Legacy boolean setting
				fullscreen = prefs.getBoolean(pkgName + Constants.PREF_FULLSCREEN, false)
						? Constants.FULLSCREEN_FORCE : Constants.FULLSCREEN_DEFAULT;
			}
			final int fullscreenSelection = fullscreen;
			Spinner spnFullscreen = findViewById(R.id.spnFullscreen);
			// Note: the order of these items must match the Common.FULLSCREEN_... constants
			String[] fullscreenArray;
			fullscreenArray = new String[] {
					getString(R.string.settings_default),
					getString(R.string.settings_force),
					getString(R.string.settings_prevent),
					getString(R.string.settings_immersive)
			};

			List<String> lstFullscreen = Arrays.asList(fullscreenArray);
			ArrayAdapter<String> fullscreenAdapter = new ArrayAdapter<>(this,
					android.R.layout.simple_spinner_item, lstFullscreen);
			fullscreenAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
			spnFullscreen.setAdapter(fullscreenAdapter);
			spnFullscreen.setSelection(fullscreenSelection);
		}

        // Update Auto Hide field
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ((CheckBox) findViewById(R.id.chkAutoHide)).setChecked(prefs.getBoolean(pkgName + Constants.PREF_AUTO_HIDE_FULLSCREEN, false));
        } else {
            findViewById(R.id.chkAutoHide).setVisibility(View.GONE);
        }

		// Update No Title field
		((CheckBox) findViewById(R.id.chkNoTitle)).setChecked(prefs.getBoolean(pkgName + Constants.PREF_NO_TITLE, false));

		// Update Allow On Lockscreen field
		((CheckBox) findViewById(R.id.chkAllowOnLockscreen)).setChecked(prefs.getBoolean(pkgName + Constants.PREF_ALLOW_ON_LOCKSCREEN, false));

		// Update Screen On field
		((CheckBox) findViewById(R.id.chkScreenOn)).setChecked(prefs.getBoolean(pkgName + Constants.PREF_SCREEN_ON, false));

		// Load and render current screen setting + possible options
		int orientation = prefs.getInt(pkgName + Constants.PREF_ORIENTATION, 0);
		if (orientation < 0 || orientation >= Constants.orientationCodes.length)
			orientation = 0;
		final int selectedOrientation = orientation;

		Spinner spnOrientation = findViewById(R.id.spnOrientation);
		List<String> lstOrientations = new ArrayList<>(Constants.orientationLabels.length);
		for (int j = 0; j < Constants.orientationLabels.length; j++)
			lstOrientations.add(getString(Constants.orientationLabels[j]));
		ArrayAdapter<String> orientationAdapter = new ArrayAdapter<>(this,
				android.R.layout.simple_spinner_item, lstOrientations);
		orientationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		spnOrientation.setAdapter(orientationAdapter);
		spnOrientation.setSelection(selectedOrientation);

		// Setting for making the app resident in memory
		((CheckBox) findViewById(R.id.chkResident)).setChecked(prefs.getBoolean(pkgName + Constants.PREF_RESIDENT, false));

		// Setting for disabling fullscreen IME
		((CheckBox) findViewById(R.id.chkNoFullscreenIME)).setChecked(prefs.getBoolean(pkgName + Constants.PREF_NO_FULLSCREEN_IME, false));

		// Setup Ongoing Notifications settings
		{
			int ongoingNotifs = prefs.getInt(pkgName + Constants.PREF_ONGOING_NOTIF, Constants.ONGOING_NOTIF_DEFAULT);
			Spinner spnOngoingNotif = findViewById(R.id.spnOngoingNotifications);
			// Note: the order of these items must match the Common.ONGOING_NOTIF_... constants
			String[] ongoingNotifArray = new String[] {
						getString(R.string.settings_default),
						getString(R.string.settings_force),
						getString(R.string.settings_prevent) };

			List<String> lstOngoingNotif = Arrays.asList(ongoingNotifArray);
			ArrayAdapter<String> ongoingNotifAdapter = new ArrayAdapter<>(this,
					android.R.layout.simple_spinner_item, lstOngoingNotif);
			ongoingNotifAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
			spnOngoingNotif.setAdapter(ongoingNotifAdapter);
			spnOngoingNotif.setSelection(ongoingNotifs);
		}

		// Update Insistent Notifications field
		((CheckBox) findViewById(R.id.chkInsistentNotifications)).setChecked(prefs.getBoolean(
				pkgName + Constants.PREF_INSISTENT_NOTIF, false));

		// Update Mute field
		((CheckBox) findViewById(R.id.chkMute)).setChecked(prefs.getBoolean(pkgName + Constants.PREF_MUTE, false));

		// Update Legacy Menu field
		((CheckBox) findViewById(R.id.chkLegacyMenu)).setChecked(prefs.getBoolean(
				pkgName + Constants.PREF_LEGACY_MENU, false));

		// Update Recent Tasks field
		((CheckBox) findViewById(R.id.chkRecentTasks)).setChecked(prefs.getBoolean(pkgName +
				Constants.PREF_RECENT_TASKS, false));

		// Setting for permissions revoking
		allowRevoking = prefs.getBoolean(pkgName + Constants.PREF_REVOKEPERMS, false);
		disabledPermissions = prefs.getStringSet(pkgName + Constants.PREF_REVOKELIST, new HashSet<>());

		// Setup recents mode options
		final int selectedRecentsMode = prefs.getInt(pkgName + Constants.PREF_RECENTS_MODE, Constants.PREF_RECENTS_DEFAULT);
		// Note: the order of these items must match the Common.RECENTS_... constants
		String[] recentsModeArray = new String[] { getString(R.string.settings_default),
				getString(R.string.settings_force), getString(R.string.settings_prevent) };

		Spinner spnRecentsMode = findViewById(R.id.spnRecentsMode);
		List<String> lstRecentsMode = Arrays.asList(recentsModeArray);
		ArrayAdapter<String> recentsModeAdapter = new ArrayAdapter<>(this,
				android.R.layout.simple_spinner_item, lstRecentsMode);
		recentsModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		spnRecentsMode.setAdapter(recentsModeAdapter);
		spnRecentsMode.setSelection(selectedRecentsMode);

		Button btnPermissions = findViewById(R.id.btnPermissions);
		btnPermissions.setOnClickListener(v -> {
			// set up permissions editor
			try {
				PermissionSettings permsDlg = new PermissionSettings(this, pkgName, allowRevoking, disabledPermissions);
				permsDlg.setOnOkListener(obj -> {
					allowRevoking = permsDlg.getRevokeActive();
					disabledPermissions.clear();
					disabledPermissions.addAll(permsDlg.getDisabledPermissions());
				});
				permsDlg.display();
			} catch (NameNotFoundException ignored) {
			}
		});

		settingKeys = getSettingKeys();
		initialSettings = getSettings();
		onStartSettings = getSettings();
	}

	private Set<String> getSettingKeys() {
		HashSet<String> settingKeys = new HashSet<>();
		settingKeys.add(pkgName + Constants.PREF_ACTIVE);
		settingKeys.add(pkgName + Constants.PREF_DPI);
		settingKeys.add(pkgName + Constants.PREF_FONT_SCALE);
		settingKeys.add(pkgName + Constants.PREF_SCREEN);
		settingKeys.add(pkgName + Constants.PREF_XLARGE);
		settingKeys.add(pkgName + Constants.PREF_LTR);
		settingKeys.add(pkgName + Constants.PREF_SCREENSHOT);
		settingKeys.add(pkgName + Constants.PREF_LOCALE);
		settingKeys.add(pkgName + Constants.PREF_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			settingKeys.add(pkgName + Constants.PREF_AUTO_HIDE_FULLSCREEN);
		}
		settingKeys.add(pkgName + Constants.PREF_NO_TITLE);
		settingKeys.add(pkgName + Constants.PREF_ALLOW_ON_LOCKSCREEN);
		settingKeys.add(pkgName + Constants.PREF_SCREEN_ON);
		settingKeys.add(pkgName + Constants.PREF_ORIENTATION);
		settingKeys.add(pkgName + Constants.PREF_RESIDENT);
		settingKeys.add(pkgName + Constants.PREF_NO_FULLSCREEN_IME);
		settingKeys.add(pkgName + Constants.PREF_INSISTENT_NOTIF);
		settingKeys.add(pkgName + Constants.PREF_ONGOING_NOTIF);
		settingKeys.add(pkgName + Constants.PREF_RECENTS_MODE);
		settingKeys.add(pkgName + Constants.PREF_MUTE);
		settingKeys.add(pkgName + Constants.PREF_LEGACY_MENU);
		settingKeys.add(pkgName + Constants.PREF_RECENT_TASKS);
		settingKeys.add(pkgName + Constants.PREF_REVOKEPERMS);
		settingKeys.add(pkgName + Constants.PREF_REVOKELIST);
		return settingKeys;
	}

	private Map<String, Object> getSettings() {

		Map<String, Object> settings = new HashMap<>();
		if (swtActive.isChecked()) {
			settings.put(pkgName + Constants.PREF_ACTIVE, true);

			int dpi;
			try {
				dpi = Integer.parseInt(((EditText) findViewById(R.id.txtDPI)).getText().toString());
			} catch (Exception ex) {
				dpi = 0;
			}
			if (dpi != 0)
				settings.put(pkgName + Constants.PREF_DPI, dpi);

			int fontScale;
			try {
				fontScale = Integer.parseInt(((EditText) findViewById(R.id.txtFontScale)).getText().toString());
			} catch (Exception ex) {
				fontScale = 0;
			}
			if (fontScale != 0 && fontScale != 100)
				settings.put(pkgName + Constants.PREF_FONT_SCALE, fontScale);

			int screen = ((Spinner) findViewById(R.id.spnScreen)).getSelectedItemPosition();
			if (screen > 0)
				settings.put(pkgName + Constants.PREF_SCREEN, screen);

			if (((CheckBox) findViewById(R.id.chkXlarge)).isChecked())
				settings.put(pkgName + Constants.PREF_XLARGE, true);

			if (((CheckBox) findViewById(R.id.chkLTR)).isChecked())
				settings.put(pkgName + Constants.PREF_LTR, true);

			int screenshot = ((Spinner) findViewById(R.id.spnScreenshot)).getSelectedItemPosition();
			if (screenshot > 0)
				settings.put(pkgName + Constants.PREF_SCREENSHOT, screenshot);

			int selectedLocalePos = ((Spinner) findViewById(R.id.spnLocale)).getSelectedItemPosition();
			if (selectedLocalePos > 0)
				settings.put(pkgName + Constants.PREF_LOCALE, localeList.getLocale(selectedLocalePos));

			int fullscreen = ((Spinner) findViewById(R.id.spnFullscreen)).getSelectedItemPosition();
			if (fullscreen > 0) {
				settings.put(pkgName + Constants.PREF_FULLSCREEN, fullscreen);
			}

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && ((CheckBox) findViewById(R.id.chkAutoHide)).isChecked())
				settings.put(pkgName + Constants.PREF_AUTO_HIDE_FULLSCREEN, true);

			if (((CheckBox) findViewById(R.id.chkNoTitle)).isChecked())
				settings.put(pkgName + Constants.PREF_NO_TITLE, true);

			if (((CheckBox) findViewById(R.id.chkAllowOnLockscreen)).isChecked())
				settings.put(pkgName + Constants.PREF_ALLOW_ON_LOCKSCREEN, true);

			if (((CheckBox) findViewById(R.id.chkScreenOn)).isChecked())
				settings.put(pkgName + Constants.PREF_SCREEN_ON, true);

			int orientation = ((Spinner) findViewById(R.id.spnOrientation)).getSelectedItemPosition();
			if (orientation > 0)
				settings.put(pkgName + Constants.PREF_ORIENTATION, orientation);

			if (((CheckBox) findViewById(R.id.chkResident)).isChecked())
				settings.put(pkgName + Constants.PREF_RESIDENT, true);

			if (((CheckBox) findViewById(R.id.chkNoFullscreenIME)).isChecked())
				settings.put(pkgName + Constants.PREF_NO_FULLSCREEN_IME, true);

			if (((CheckBox) findViewById(R.id.chkInsistentNotifications)).isChecked())
				settings.put(pkgName + Constants.PREF_INSISTENT_NOTIF, true);

			int ongoingNotif = ((Spinner) findViewById(R.id.spnOngoingNotifications)).getSelectedItemPosition();
			if (ongoingNotif > 0)
				settings.put(pkgName + Constants.PREF_ONGOING_NOTIF, ongoingNotif);

			int recentsMode = ((Spinner) findViewById(R.id.spnRecentsMode)).getSelectedItemPosition();
			if (recentsMode > 0)
				settings.put(pkgName + Constants.PREF_RECENTS_MODE, recentsMode);

			if (((CheckBox) findViewById(R.id.chkMute)).isChecked())
				settings.put(pkgName + Constants.PREF_MUTE, true);

			if (((CheckBox) findViewById(R.id.chkLegacyMenu)).isChecked())
				settings.put(pkgName + Constants.PREF_LEGACY_MENU, true);

			if (((CheckBox) findViewById(R.id.chkRecentTasks)).isChecked())
				settings.put(pkgName + Constants.PREF_RECENT_TASKS, true);

			if (allowRevoking)
				settings.put(pkgName + Constants.PREF_REVOKEPERMS, true);

			if (disabledPermissions.size() > 0)
				settings.put(pkgName + Constants.PREF_REVOKELIST, new HashSet<>(disabledPermissions));
		}
		return settings;
	}

	@Override
	public void onBackPressed() {
		// If form wasn't changed, exit without prompting
		if (getSettings().equals(initialSettings)) {
			finish();
			return;
		}

		// Require confirmation to exit the screen and lose configuration changes
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle(R.string.settings_unsaved_title);
		builder.setIconAttribute(android.R.attr.alertDialogIcon);
		builder.setMessage(R.string.settings_unsaved_detail);
		builder.setPositiveButton(R.string.common_button_ok, (dialog, which) -> {
			onStartSettings = getSettings();
			finish();
		});
		builder.setNegativeButton(R.string.common_button_cancel, null);
		builder.show();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		setResult(RESULT_OK, parentIntent);
		if(!getSettings().equals(onStartSettings)) {
			MainActivity.refreshAppsAfterChanges(false);
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.menu_app, menu);
		updateMenuEntries(this, menu, pkgName);
		return true;
	}

	public static void updateMenuEntries(Activity context, Menu menu, String pkgName) {
		if (context.getPackageManager().getLaunchIntentForPackage(pkgName) == null) {
			menu.findItem(R.id.menu_app_launch).setEnabled(false);
			if (Utils.isPortrait(context)) {
				setItemDisabled(menu, 1);
			} else {
				Drawable icon = Objects.requireNonNull(menu.findItem(R.id.menu_app_launch).getIcon()).mutate();
				icon.setColorFilter(BlendModeColorFilterCompat.createBlendModeColorFilterCompat(Color.GRAY, BlendModeCompat.SRC_IN));
				menu.findItem(R.id.menu_app_launch).setIcon(icon);
			}
		}

		boolean hasMarketLink = false;
		try {
			PackageManager pm = context.getPackageManager();
			String installer;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				installer = pm.getInstallSourceInfo(pkgName).getInstallingPackageName();
			} else {
				installer = pm.getInstallerPackageName(pkgName);
			}

			if (installer != null)
				hasMarketLink = installer.equals("com.android.vending") || installer.contains("google");
		} catch (Exception ignored) {
		}

		menu.findItem(R.id.menu_app_store).setEnabled(hasMarketLink);

		if (!hasMarketLink) {
			setItemDisabled(menu, 3);
		}
	}

	private static void setItemDisabled(Menu menu, int id) {
		MenuItem item = menu.getItem(id);
		SpannableString spanString = new SpannableString(Objects.requireNonNull(item.getTitle()).toString());
		spanString.setSpan(new ForegroundColorSpan(Color.GRAY), 0, spanString.length(), 0);
		item.setTitle(spanString);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.menu_save) {
			saveSettings();
		} else if (item.getItemId() == R.id.menu_app_launch) {
			Intent LaunchIntent = getPackageManager().getLaunchIntentForPackage(pkgName);
			startActivity(LaunchIntent);
		} else if (item.getItemId() == R.id.menu_app_settings) {
			startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
									Uri.parse("package:" + pkgName)));
		} else if (item.getItemId() == R.id.menu_app_store) {
			startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkgName)));
		} else if (item.getItemId() == R.id.menu_reboot) {
			confirmReboot();
        }
		return super.onOptionsItemSelected(item);
	}

	@SuppressLint("MissingPermission")
	private void confirmReboot() {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle(R.string.menu_reboot_confirm);
		builder.setMessage(R.string.menu_reboot_confirm_desc);
		builder.setPositiveButton(R.string.common_button_ok, (dialog, which) -> {
			try {
				((PowerManager) Objects.requireNonNull(this.
						getSystemService(Context.POWER_SERVICE))).reboot(null);
			} catch (Exception e) {
				Log.e(Constants.TAG, e.toString());
				e.printStackTrace();
			}
		});
		builder.setNegativeButton(R.string.common_button_cancel, (null));
		builder.create().show();
	}

	@SuppressWarnings("unchecked")
	public void saveSettings() {
		Editor prefsEditor = prefs.edit();
		Map<String, Object> newSettings = getSettings();
		for (String key : settingKeys) {
			Object value = newSettings.get(key);
			if (value == null) {
				prefsEditor.remove(key);
			} else {
				if (value instanceof Boolean) {
					prefsEditor.putBoolean(key, (Boolean) value);
				} else if (value instanceof Integer) {
					prefsEditor.putInt(key, (Integer) value);
				} else if (value instanceof String) {
					prefsEditor.putString(key, (String) value);
				} else if (value instanceof Set) {
					prefsEditor.remove(key);
					// Commit and reopen the editor, as it seems to be bugged when updating a StringSet
					prefsEditor.apply();
					prefsEditor = prefs.edit();
					prefsEditor.putStringSet(key, (Set<String>) value);
				} else {
					// Should never happen
					throw new IllegalStateException("Invalid setting type: " + key + "=" + value);
				}
			}
		}
		prefsEditor.apply();

		// Update saved settings to detect modifications later
		initialSettings = newSettings;

		// Check if in addition to saving the settings, the app should also be killed
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle(R.string.settings_apply_title);
		builder.setMessage(R.string.settings_apply_detail);
		builder.setPositiveButton(R.string.common_button_ok, (dialog, which) -> {
			// Send the broadcast requesting to kill the app
			Intent applyIntent = new Intent(Constants.MY_PACKAGE_NAME + ".UPDATE_PERMISSIONS");
			applyIntent.putExtra("action", Constants.ACTION_PERMISSIONS);
			applyIntent.putExtra("Package", pkgName);
			applyIntent.putExtra("Kill", true);
			sendBroadcast(applyIntent, Constants.MY_PACKAGE_NAME + ".BROADCAST_PERMISSION");
			dialog.dismiss();
		});
		builder.setNegativeButton(R.string.common_button_cancel, (dialog, which) -> {
			// Send the broadcast but not requesting kill
			Intent applyIntent = new Intent(Constants.MY_PACKAGE_NAME + ".UPDATE_PERMISSIONS");
			applyIntent.putExtra("action", Constants.ACTION_PERMISSIONS);
			applyIntent.putExtra("Package", pkgName);
			applyIntent.putExtra("Kill", false);
			sendBroadcast(applyIntent, Constants.MY_PACKAGE_NAME + ".BROADCAST_PERMISSION");
			dialog.dismiss();
		});
		builder.create().show();
	}
}
