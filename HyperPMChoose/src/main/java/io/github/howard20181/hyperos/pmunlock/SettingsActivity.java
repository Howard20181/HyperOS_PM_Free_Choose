package io.github.howard20181.hyperos.pmunlock;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;

import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

import io.github.libxposed.service.XposedService;

public class SettingsActivity extends Activity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        setContentView(R.layout.settings);
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .add(R.id.fragment_container, new SettingsFragment()).commit();
        }
    }

    public static class SettingsFragment extends PreferenceFragment implements App.ServiceStateListener {
        ListPreference unlockPref;
        ListPreference customNamePref;
        SwitchPreference unlockMarketPref;
        private XposedService mService;

        private void applyServiceStateToPrefs(XposedService service) {
            this.mService = service;
            if (unlockPref == null || customNamePref == null || unlockMarketPref == null) {
                return;
            }

            if (service == null) {
                unlockPref.setEnabled(false);
                customNamePref.setEnabled(false);
                unlockMarketPref.setEnabled(false);
                return;
            }

            var remotePrefs = service.getRemotePreferences("conf");
            unlockPref.setValue(remotePrefs.getString("package_installer_unlock", "off"));
            unlockPref.setEnabled(true);
            customNamePref.setValue(remotePrefs.getString("package_installer_custom_package_name", ""));
            customNamePref.setEnabled("custom".equals(unlockPref.getValue()));
            unlockMarketPref.setChecked(remotePrefs.getBoolean("unlock_choose_market_app", false));
            unlockMarketPref.setEnabled(true);
        }

        private boolean pushRemoteConfig(String changedKey, Object newValue) {
            if (mService == null) return false;

            var remotePrefs = mService.getRemotePreferences("conf");

            var editor = remotePrefs.edit();
            if (newValue instanceof String value) {
                editor.putString(changedKey, value.trim());
            } else if (newValue instanceof Boolean value) {
                editor.putBoolean(changedKey, value);
            }
            editor.apply();
            return true;
        }

        public static List<ResolveInfo> queryApkInstallers(PackageManager packageManager) {
            var intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setDataAndType(
                    Uri.parse("content://dummy.apk"),
                    App.PACKAGE_MIME_TYPE
            );
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            return packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.preferences);
            unlockPref = (ListPreference) findPreference("package_installer_unlock");
            customNamePref = (ListPreference) findPreference("package_installer_custom_package_name");
            unlockMarketPref = (SwitchPreference) findPreference("unlock_choose_market_app");
            if (unlockPref != null) {
                unlockPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    String value = String.valueOf(newValue).trim();
                    customNamePref.setEnabled("custom".equals(value));
                    return pushRemoteConfig("package_installer_unlock", value);
                });

            }
            if (customNamePref != null) {
                var pm = getContext().getPackageManager();
                var apkInstallers = queryApkInstallers(pm);
                var installerMap = new LinkedHashMap<String, CharSequence>();
                var duplicatedPackages = new HashSet<String>();
                installerMap.put("", getString(R.string.package_installer_custom_package_name_default));
                for (var installer : apkInstallers) {
                    var packageName = installer.activityInfo.packageName;
                    var existing = installerMap.putIfAbsent(
                            packageName,
                            installer.activityInfo.applicationInfo.loadLabel(pm)
                    );
                    if (existing != null) {
                        duplicatedPackages.add(packageName);
                    }
                }
                // Remove any package that appears multiple times in query results.
                // Otherwise, the system will crash because it cannot resolve the default package opening method.
                duplicatedPackages.forEach(installerMap::remove);
                customNamePref.setEntries(installerMap.values().toArray(new CharSequence[0]));
                customNamePref.setEntryValues(installerMap.keySet().toArray(new CharSequence[0]));
                customNamePref.setOnPreferenceChangeListener((preference, newValue)
                        -> pushRemoteConfig("package_installer_custom_package_name", newValue));
            }
            if (unlockMarketPref != null) {
                unlockMarketPref.setOnPreferenceChangeListener((preference, newValue)
                        -> pushRemoteConfig("unlock_choose_market_app", newValue));
            }
        }

        @Override
        public void onStart() {
            super.onStart();
            App.addServiceStateListener(this);
        }

        @Override
        public void onStop() {
            App.removeServiceStateListener(this);
            super.onStop();
        }

        @Override
        public void onServiceStateChanged(@Nullable XposedService service) {
            applyServiceStateToPrefs(service);
        }
    }
}
