package com.mohammadag.xposedpreferenceinjector;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.preference.PreferenceActivity.Header;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class XposedMod implements IXposedHookLoadPackage {
	@SuppressWarnings("unused")
	private static final String TAG = "InjectXposedPreference";
	private static final String KEY_LOADER = "ModuleLoader";
	private static final String KEY_HEADER_LIST = "HeaderList";
	private static Header sSectionHeader;
	private static Header sMoreHeader;
	private static WeakReference<Resources> sRefResources;

	@Override
	public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
		if (!lpparam.packageName.equals("com.android.settings"))
			return;

		Class<?> SettingsClazz = findClass("com.android.settings.Settings", lpparam.classLoader);
		final Class<?> HeaderAdapter = findClass("com.android.settings.Settings$HeaderAdapter", lpparam.classLoader);

		findAndHookMethod(SettingsClazz, "onCreate", Bundle.class, new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				PreferenceActivity activity = (PreferenceActivity) param.thisObject;

				List<Header> appsHeader = new ArrayList<Header>();
				XposedHelpers.setAdditionalInstanceField(activity, KEY_HEADER_LIST, appsHeader);

				ModuleLoader loader = new ModuleLoader(activity, appsHeader);
				XposedHelpers.setAdditionalInstanceField(activity, KEY_LOADER, loader);
			}
		});

		XposedHelpers.findAndHookMethod(SettingsClazz, "onResume", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				PreferenceActivity activity = (PreferenceActivity) param.thisObject;
				ModuleLoader loader = (ModuleLoader) XposedHelpers.getAdditionalInstanceField(activity, KEY_LOADER);
				loader.updateModuleList();
				loader.startObserver();
			}
		});

		XposedHelpers.findAndHookMethod(SettingsClazz, "onPause", new XC_MethodHook() {

			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				PreferenceActivity activity = (PreferenceActivity) param.thisObject;
				ModuleLoader loader = (ModuleLoader) XposedHelpers.getAdditionalInstanceField(activity, KEY_LOADER);
				loader.stopTask();
				loader.stopObserver();
			}
		});

		XposedBridge.hookAllMethods(SettingsClazz, "updateHeaderList", new XC_MethodHook() {
			@SuppressWarnings("unchecked")
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				PreferenceActivity activity = (PreferenceActivity) param.thisObject;
				List<Header> headers = (List<Header>) param.args[0];
				List<Header> appsHeader = (List<Header>) XposedHelpers.getAdditionalInstanceField(activity,
						KEY_HEADER_LIST);
				if (appsHeader == null)
					appsHeader = new ArrayList<Header>();

				// Find headerIndex
				int headerIndex = findHeaderIndex(activity, headers, "system_section");
				if (headerIndex == -1) {
					headerIndex = headers.size();
				} else {
					// Inject BEFORE this section.
					headerIndex--;
				}

				// Add section
				if (sSectionHeader == null) {
					sSectionHeader = new Header();
					sSectionHeader.title = getStringMyPackage(activity, R.string.xposed_modules, "Xposed Modules");
				}
				headers.add(headerIndex++, sSectionHeader);

				// Add apps header
				headers.addAll(headerIndex++, appsHeader);
				headerIndex += appsHeader.size() - 1;

				// Add more header
				if (sMoreHeader == null) {
					PackageManager pm = activity.getPackageManager();
					sMoreHeader = new Header();
					String moreText = getStringSettingsPackage(activity, "wifi_more", "More...");
					sMoreHeader.title = moreText;
					sMoreHeader.iconRes = android.R.color.transparent;
					sMoreHeader.intent = pm.getLaunchIntentForPackage("de.robv.android.xposed.installer");
				}
				headers.add(headerIndex++, sMoreHeader);

				param.args[0] = headers;
			}
		});

		/*
		 * We can only pass resIds above, so we override the getView method to
		 * load the icon manually
		 */
		findAndHookMethod(HeaderAdapter, "getView", int.class, View.class, ViewGroup.class, new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				BaseAdapter adapter = (BaseAdapter) param.thisObject;
				Header header = (Header) adapter.getItem((Integer) param.args[0]);
				if (header.extras != null) {
					boolean isXposedModule = header.extras.getBoolean("xposed_module", false);
					if (isXposedModule) {
						String packageName = header.extras.getString("xposed_package_name");
						View view = (View) param.getResult();
						ImageView icon = (ImageView) XposedHelpers.getObjectField(view.getTag(), "icon");
						// TODO Move to async
						icon.setImageDrawable(view.getContext().getPackageManager().getApplicationIcon(packageName));
					}
				}
			}
		});

	}

	static Header getHeaderFromAppInfo(PackageManager pm, ApplicationInfo info) {
		return getHeaderFromAppInfo(pm, info, null);
	}

	public static Header getHeaderFromAppInfo(PackageManager pm, ApplicationInfo info, String title) {
		Header header = new Header();
		header.title = (title == null) ? info.loadLabel(pm) : title;
		header.iconRes = android.R.drawable.sym_def_app_icon;

		Bundle extras = new Bundle();
		extras.putString("xposed_package_name", info.packageName);
		extras.putBoolean("xposed_module", true);
		header.extras = extras;

		return header;
	}

	public static String getStringSettingsPackage(Activity activity, String name, String def_text) {
		Resources res = activity.getResources();
		try {
			int resId = res.getIdentifier(name, "string", activity.getPackageName());
			if (resId == 0)
				return def_text;
			return res.getString(resId);
		} catch (Exception e) {
			return def_text;
		}
	}

	public static String getStringMyPackage(Context context, int resId, String def_text) {
		Resources res;
		if (sRefResources == null || sRefResources.get() == null) {
			PackageManager pm = context.getPackageManager();
			try {
				res = pm.getResourcesForApplication(XposedMod.class.getPackage().getName());
			} catch (NameNotFoundException e) {
				return def_text;
			}
			sRefResources = new WeakReference<Resources>(res);
		} else {
			res = sRefResources.get();
		}
		try {
			return res.getString(resId);
		} catch (Exception e) {
			return def_text;
		}
	}

	public static int findHeaderIndex(Activity activity, List<Header> headers, String headerName) {
		int headerIndex = -1;
		int resId = activity.getResources().getIdentifier(headerName, "id", activity.getPackageName());
		if (resId != 0) {
			int i = 0;
			int size = headers.size();
			while (i < size) {
				Header header = headers.get(i);
				int id = (int) header.id;
				if (id == resId) {
					headerIndex = i + 1;
					break;
				}
				i++;
			}
		}
		return headerIndex;

	}

}