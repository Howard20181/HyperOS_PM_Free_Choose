package io.github.tehcneko.hyperinstaller;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.annotations.AfterInvocation;
import io.github.libxposed.api.annotations.BeforeInvocation;
import io.github.libxposed.api.annotations.XposedHooker;

@SuppressLint("PrivateApi")
public class Hooker extends XposedModule {

    private static final String TAG = "HyperInstaller";
    private static final String PACKAGE_MIME_TYPE = "application/vnd.android.package-archive";

    private static Field fIsInternationalBuildBoolean;
    private static Field fCurrentPackageInstaller;
    private static String mCurrentPackageInstaller;
    private static String replacePackageInstaller = "off";
    private static String customPackageInstallerName = "";

    public Hooker(@NonNull XposedInterface base, @NonNull ModuleLoadedParam param) {
        super(base, param);
    }

    @Override
    public void onSystemServerLoaded(@NonNull SystemServerLoadedParam param) {
        var classLoader = param.getClassLoader();
        try {
            var PackageManagerServiceStubClass = classLoader.loadClass("com.android.server.pm.PackageManagerServiceStub");
            fIsInternationalBuildBoolean = PackageManagerServiceStubClass.getDeclaredField("IS_INTERNATIONAL_BUILD");
            fIsInternationalBuildBoolean.setAccessible(true);
            var PackageManagerServiceImplClass = classLoader.loadClass("com.android.server.pm.PackageManagerServiceImpl");
            fCurrentPackageInstaller = PackageManagerServiceImplClass.getDeclaredField("mCurrentPackageInstaller");
            fCurrentPackageInstaller.setAccessible(true);
        } catch (ClassNotFoundException | NoSuchFieldException e) {
            log("Failed to find IS_INTERNATIONAL_BUILD field", e);
        }
        try {
            hookPackageManagerServiceImpl(classLoader);
        } catch (Throwable t) {
            log("Failed to hook PackageManagerServiceImpl", t);
        }
        try {
            hookIsCTS(classLoader);
        } catch (Throwable t) {
            log("Failed to hook isCTS", t);
        }
    }

    private void hookPackageManagerServiceImpl(ClassLoader classLoader) throws ClassNotFoundException {
        var packageManagerServiceImpl = classLoader.loadClass("com.android.server.pm.PackageManagerServiceImpl");
        var methods = packageManagerServiceImpl.getDeclaredMethods();
        for (var method : methods) {
            var name = method.getName();
            if ("hookChooseBestActivity".equals(name) ||
                    "updateDefaultPkgInstallerLocked".equals(name) ||
                    "assertValidApkAndInstaller".equals(name)) {
                Log.d(TAG, "hooking method " + name);
                hook(method, PackageManagerServiceImplHooker.class);
                if ("hookChooseBestActivity".equals(name)) {
                    var prefs = getRemotePreferences("conf");
                    replacePackageInstaller = prefs.getString("package_installer_unlock", "off");
                    customPackageInstallerName = prefs.getString("package_installer_custom_package_name", "").trim();
                    prefs.registerOnSharedPreferenceChangeListener((sharedPreferences, key) -> {
                        if ("package_installer_unlock".equals(key)) {
                            replacePackageInstaller = sharedPreferences.getString(key, "off");
                        } else if ("package_installer_custom_package_name".equals(key)) {
                            customPackageInstallerName = sharedPreferences.getString(key, "").trim();
                        }
                    });
                    hook(method, ChooseBestActivityHooker.class);
                }
                deoptimize(method);
            }
        }
    }

    private void hookIsCTS(ClassLoader classLoader) throws NoSuchMethodException, ClassNotFoundException {
        var packageManagerServiceImpl = classLoader.loadClass("com.android.server.pm.PackageManagerServiceImpl");
        var isCTSMethod = packageManagerServiceImpl.getDeclaredMethod("isCTS");
        hook(isCTSMethod, IsCTSHooker.class);
    }

    @XposedHooker
    private static class IsCTSHooker implements Hooker {
        public static boolean fakeCTS = false;

        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) throws Throwable {
            if (fakeCTS) {
                callback.returnAndSkip(true);
            }
        }
    }

    @XposedHooker
    private static class PackageManagerServiceImplHooker implements Hooker {

        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) throws Throwable {
            IsCTSHooker.fakeCTS = true;
        }

        @AfterInvocation
        public static void after(@NonNull AfterHookCallback callback) throws Throwable {
            IsCTSHooker.fakeCTS = false;
        }
    }

    @XposedHooker
    private static class ChooseBestActivityHooker implements Hooker {

        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) throws Throwable {
            if (fIsInternationalBuildBoolean != null && "any".equals(replacePackageInstaller)) {
                fIsInternationalBuildBoolean.setBoolean(null, true);
            } else if ("custom".equals(replacePackageInstaller)) {
                Intent intent = (Intent) callback.getArgs()[0];
                if (PACKAGE_MIME_TYPE.equals(intent.getType()) && "android.intent.action.VIEW".equals(intent.getAction()) && !customPackageInstallerName.isEmpty()) {
                    var thisObject = callback.getThisObject();
                    mCurrentPackageInstaller = (String) fCurrentPackageInstaller.get(thisObject);
                    fCurrentPackageInstaller.set(thisObject, customPackageInstallerName);
                }
            }
        }

        @AfterInvocation
        public static void after(@NonNull AfterHookCallback callback) throws Throwable {
            if (fIsInternationalBuildBoolean != null && "any".equals(replacePackageInstaller)) {
                fIsInternationalBuildBoolean.setBoolean(null, false);
            } else if ("custom".equals(replacePackageInstaller)) {
                Intent intent = (Intent) callback.getArgs()[0];
                if (PACKAGE_MIME_TYPE.equals(intent.getType()) && "android.intent.action.VIEW".equals(intent.getAction())) {
                    var thisObject = callback.getThisObject();
                    fCurrentPackageInstaller.set(thisObject, mCurrentPackageInstaller);
                }
            }
        }
    }
}
