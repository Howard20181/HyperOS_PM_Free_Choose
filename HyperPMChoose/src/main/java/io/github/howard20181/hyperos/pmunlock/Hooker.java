package io.github.howard20181.hyperos.pmunlock;

import static io.github.howard20181.hyperos.pmunlock.App.PACKAGE_MIME_TYPE;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedModule;

@SuppressLint("PrivateApi")
public class Hooker extends XposedModule {

    private static final String TAG = "HyperInstaller";
    private static final AtomicReference<String> replacePackageInstaller = new AtomicReference<>("off");
    private static final AtomicReference<String> customPackageInstallerName = new AtomicReference<>("");
    private static final AtomicReference<Boolean> unlockMarket = new AtomicReference<>(false);

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        var prefs = getRemotePreferences("conf");
        replacePackageInstaller.set(prefs.getString("package_installer_unlock", "off"));
        customPackageInstallerName.set(prefs.getString("package_installer_custom_package_name", "").trim());
        unlockMarket.set(prefs.getBoolean("unlock_choose_market_app", false));
        prefs.registerOnSharedPreferenceChangeListener((sharedPreferences, key) -> {
            if ("package_installer_unlock".equals(key)) {
                replacePackageInstaller.set(sharedPreferences.getString(key, "off"));
            } else if ("package_installer_custom_package_name".equals(key)) {
                customPackageInstallerName.set(sharedPreferences.getString(key, "").trim());
            } else if ("unlock_choose_market_app".equals(key)) {
                unlockMarket.set(sharedPreferences.getBoolean(key, false));
            }
        });
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        var classLoader = param.getClassLoader();
        try {
            hookPackageManagerServiceImpl(classLoader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook PackageManagerServiceImpl", t);
        }
        try {
            hookXSpaceConstant(classLoader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook XSpaceConstant", t);
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        var pn = param.getPackageName();
        var classLoader = param.getClassLoader();
        if ("com.miui.securitycore".equals(pn)) {
            hookXSpaceConstant(classLoader);
        }
    }

    private void hookXSpaceConstant(ClassLoader classLoader) {
        try {
            var XSpaceConstantClass = classLoader.loadClass("miui.securityspace.XSpaceConstant");
            var requiredAppsField = XSpaceConstantClass.getDeclaredField("REQUIRED_APPS");
            requiredAppsField.setAccessible(true);
            var requiredApps = (ArrayList<String>) requiredAppsField.get(null);
            if (requiredApps != null && !requiredApps.contains("com.android.packageinstaller")) {
                requiredApps.add("com.android.packageinstaller");
            }
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to add package installer to REQUIRED_APPS", e);
        }
    }

    private void hookPackageManagerServiceImpl(ClassLoader classLoader) throws ClassNotFoundException {
        AtomicBoolean fakeCTS = new AtomicBoolean(false);
        var packageManagerServiceImpl = classLoader.loadClass("com.android.server.pm.PackageManagerServiceImpl");
        var methods = packageManagerServiceImpl.getDeclaredMethods();
        var mCurrentPackageInstaller = new AtomicReference<>("");
        Field fCurrentPackageInstaller = null;
        try {
            fCurrentPackageInstaller = packageManagerServiceImpl.getDeclaredField("mCurrentPackageInstaller");
            fCurrentPackageInstaller.setAccessible(true);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to find mCurrentPackageInstaller field", e);
        }
        Field finalFCurrentPackageInstaller = fCurrentPackageInstaller;
        for (var method : methods) {
            var name = method.getName();
            switch (name) {
                case "updateDefaultPkgInstallerLocked" -> {
                    hook(method).intercept(chain -> {
                        fakeCTS.set(true);
                        if (finalFCurrentPackageInstaller != null && mCurrentPackageInstaller.get().isEmpty()
                                && finalFCurrentPackageInstaller.get(chain.getThisObject()) instanceof String currentPackageInstaller) {
                            mCurrentPackageInstaller.compareAndSet("", currentPackageInstaller);
                        }
                        try {
                            return chain.proceed();
                        } finally {
                            fakeCTS.set(false);
                        }
                    });
                    deoptimize(method);
                }
                case "assertValidApkAndInstaller" -> {
                    hook(method).intercept(chain -> {
                        fakeCTS.set(true);
                        try {
                            return chain.proceed();
                        } finally {
                            fakeCTS.set(false);
                        }
                    });
                    deoptimize(method);
                }
                case "hookChooseBestActivity" -> {
                    hook(method).intercept(chain -> {
                        try {
                            if (chain.getArg(0) instanceof Intent intent) {
                                if (finalFCurrentPackageInstaller != null
                                        && PACKAGE_MIME_TYPE.equals(intent.getType())
                                        && Intent.ACTION_VIEW.equals(intent.getAction())) {
                                    fakeCTS.set(true);
                                    switch (replacePackageInstaller.get()) {
                                        case "any":
                                            if (chain.getArg(5) instanceof ResolveInfo ri) {
                                                return ri;
                                            } else {
                                                for (var arg : chain.getArgs()) {
                                                    if (arg instanceof ResolveInfo ri) {
                                                        return ri;
                                                    }
                                                }
                                            }
                                            break;
                                        case "custom":
                                            if (!customPackageInstallerName.get().isEmpty()) {
                                                var thisObject = chain.getThisObject();
                                                finalFCurrentPackageInstaller.set(thisObject, customPackageInstallerName.get());
                                            }
                                            break;
                                    }
                                } else if (unlockMarket.get()) {
                                    String scheme = intent.getScheme();
                                    String host = intent.getData() != null ? intent.getData().getHost() : null;
                                    if (scheme != null && ((scheme.equals("mimarket")
                                            || scheme.equals("market"))
                                            && Intent.ACTION_VIEW.equals(intent.getAction())
                                            && host != null && (host.equals("details")
                                            || host.equals("search")))) {
                                        var uri = intent.getData();
                                        var uriBuilder = uri.buildUpon()
                                                .scheme("market");
                                        intent.setData(uriBuilder.build());
                                        if (chain.getArg(5) instanceof ResolveInfo ri) {
                                            return ri;
                                        }
                                    }
                                }
                            }
                            return chain.proceed();
                        } finally {
                            fakeCTS.set(false);
                            if ("custom".equals(replacePackageInstaller.get())
                                    && !mCurrentPackageInstaller.get().isEmpty()
                                    && chain.getArg(0) instanceof Intent intent) {
                                if (finalFCurrentPackageInstaller != null
                                        && PACKAGE_MIME_TYPE.equals(intent.getType())
                                        && Intent.ACTION_VIEW.equals(intent.getAction())) {
                                    finalFCurrentPackageInstaller.set(chain.getThisObject(), mCurrentPackageInstaller.get());
                                }
                            }
                        }
                    });
                    deoptimize(method);
                }
            }
        }
        try {
            var isCTSMethod = packageManagerServiceImpl.getDeclaredMethod("isCTS");
            hook(isCTSMethod).intercept(chain -> {
                if (fakeCTS.get()) {
                    return true;
                }
                return chain.proceed();
            });
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook isCTS", e);
        }
    }

    private void hookSettingsImpl(ClassLoader classLoader) {
        try {
            Class<?> XSpaceConstantClass = classLoader.loadClass("miui.securityspace.XSpaceConstant");
            var requiredAppsField = XSpaceConstantClass.getDeclaredField("REQUIRED_APPS");
            requiredAppsField.setAccessible(true);
            var settings = classLoader.loadClass("com.android.server.pm.Settings");
            // PackageManagerService service, Installer installer, int userHandle, Set<String> userTypeInstallablePackages, String[] disallowedPackages
            var createNewUserLIMethod = settings.getDeclaredMethod("createNewUserLI", classLoader.loadClass("com.android.server.pm.PackageManagerService"), classLoader.loadClass("com.android.server.pm.Installer"), int.class, Set.class, String[].class);
            hook(createNewUserLIMethod).intercept(chain -> {
                Set<String> userTypeInstallablePackages = (Set<String>) chain.getArg(3);
                String[] disallowedPackages = (String[]) chain.getArg(4);
                log(Log.DEBUG, TAG, "createNewUserLI called, userTypeInstallablePackages include installer=" + userTypeInstallablePackages.contains("com.android.packageinstaller"));
                log(Log.DEBUG, TAG, "disallowedPackages:\n" + (disallowedPackages != null ? String.join(", ", disallowedPackages) : "null"));
                return chain.proceed();
            });
            var settingsImpl = classLoader.loadClass("com.android.server.pm.SettingsImpl");
            var PackageSetting = classLoader.loadClass("com.android.server.pm.PackageSetting");
            var getPkgMethod = PackageSetting.getDeclaredMethod("getPkg");
            var getInstalledMethod = PackageSetting.getDeclaredMethod("getInstalled", int.class);
            var getPkgStateMethod = PackageSetting.getDeclaredMethod("getPkgState");
            getPkgStateMethod.setAccessible(true);
            getInstalledMethod.setAccessible(true);
            var PackageStateUnserializedClass = classLoader.loadClass("com.android.server.pm.pkg.PackageStateUnserialized");
            var isHiddenUntilInstalledMethod = PackageStateUnserializedClass.getDeclaredMethod("isHiddenUntilInstalled");
            isHiddenUntilInstalledMethod.setAccessible(true);
            var AndroidPackageClass = classLoader.loadClass("com.android.server.pm.pkg.AndroidPackage");
            var getPackageNameMethod = AndroidPackageClass.getDeclaredMethod("getPackageName");
            var checkXSpaceAppMethod = settingsImpl.getDeclaredMethod("checkXSpaceApp", PackageSetting, int.class);
            var PackageManagerServiceStubClass = classLoader.loadClass("com.android.server.pm.PackageManagerServiceStub");
            var getMethod = PackageManagerServiceStubClass.getDeclaredMethod("get");
            getMethod.setAccessible(true);
            var shouldSkipInstallForNewUserMethod = PackageManagerServiceStubClass.getDeclaredMethod("shouldSkipInstallForNewUser", String.class, int.class);
            shouldSkipInstallForNewUserMethod.setAccessible(true);
            hook(checkXSpaceAppMethod).intercept(chain -> {
                var result = chain.proceed();
                try {
                    var packageSetting = chain.getArg(0);
                    var packageName = (String) getPackageNameMethod.invoke(getPkgMethod.invoke(packageSetting));
                    if ("com.android.packageinstaller".equals(packageName)) {
                        var installed = (boolean) getInstalledMethod.invoke(packageSetting, (int) chain.getArg(1));
                        var isHiddenUntilInstalled = (boolean) isHiddenUntilInstalledMethod.invoke(getPkgStateMethod.invoke(packageSetting));
                        var shouldSkipInstallForNewUser = (boolean) getInvoker(shouldSkipInstallForNewUserMethod).invoke(getMethod.invoke(null), packageName, chain.getArg(1));
                        log(Log.INFO, TAG, "checkXSpaceApp called for package installer and return " + result + ", package installed=" + installed + ", isHiddenUntilInstalled=" + isHiddenUntilInstalled + ", shouldSkipInstallForNewUser=" + shouldSkipInstallForNewUser);
                    }
                } catch (Exception e) {
                    log(Log.ERROR, TAG, "Failed to get info in checkXSpaceApp", e);
                }
                return result;
            });
            deoptimize(checkXSpaceAppMethod);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook SettingsImpl", t);
        }
    }
}
