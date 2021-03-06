// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.AbstractExtensionPointBean;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.util.DefaultBundleService;
import com.intellij.util.ReflectionUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.xmlb.annotations.Attribute;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

public class DynamicBundle extends AbstractBundle {
  private static final Logger LOG = Logger.getInstance(DynamicBundle.class);
  private static final Method SET_PARENT = ReflectionUtil.getDeclaredMethod(ResourceBundle.class, "setParent", ResourceBundle.class);

  private static @NotNull String ourLangTag = Locale.ENGLISH.toLanguageTag();
  private static final Map<String, DynamicBundle> ourBundlesForForms = ContainerUtil.createConcurrentSoftValueMap();

  public DynamicBundle(@NotNull String pathToBundle) {
    super(pathToBundle);
  }

  // see BundleUtil
  @Override
  protected ResourceBundle findBundle(@NotNull String pathToBundle,
                                      @NotNull ClassLoader baseLoader,
                                      @NotNull ResourceBundle.Control control) {
    ResourceBundle base = super.findBundle(pathToBundle, baseLoader, control);

    if (!DefaultBundleService.isDefaultBundle()) {
      LanguageBundleEP langBundle = findLanguageBundle();
      if (langBundle != null) {
        ResourceBundle pluginBundle = super.findBundle(pathToBundle, langBundle.getLoaderForClass(), control);
        if (pluginBundle != null) {
          try {
            if (SET_PARENT != null) {
              SET_PARENT.invoke(pluginBundle, base);
            }
            return pluginBundle;
          }
          catch (Exception e) {
            LOG.warn(e);
          }
        }
      }
    }

    return base;
  }

  // todo: one language per application
  public static @Nullable LanguageBundleEP findLanguageBundle() {
    try {
      Application application = ApplicationManager.getApplication();
      if (application == null || !application.getExtensionArea().hasExtensionPoint(LanguageBundleEP.EP_NAME)) return null;
      return LanguageBundleEP.EP_NAME.findExtension(LanguageBundleEP.class);
    }
    catch (ProcessCanceledException e) {
      throw e;
    }
    catch (Exception e) {
      LOG.error(e);
      return null;
    }
  }

  public static final DynamicBundle INSTANCE = new DynamicBundle("") { };

  @SuppressWarnings("deprecation")
  public static class LanguageBundleEP extends AbstractExtensionPointBean {
    public static final ExtensionPointName<LanguageBundleEP> EP_NAME = ExtensionPointName.create("com.intellij.languageBundle");

    @Attribute("lang")
    public String lang = Locale.ENGLISH.getLanguage();
  }

  /** @deprecated used only dy GUI form builder */
  @Deprecated
  public static ResourceBundle getBundle(@NotNull String baseName) {
    Class<?> callerClass = ReflectionUtil.findCallerClass(2);
    return getBundle(baseName, callerClass == null ? DynamicBundle.class : callerClass);
  }

  /** @deprecated used only dy GUI form builder */
  @Deprecated
  public static ResourceBundle getBundle(@NotNull String baseName, @NotNull Class<?> formClass) {
    DynamicBundle dynamic = ourBundlesForForms.computeIfAbsent(baseName, s -> new DynamicBundle(s) { });
    ResourceBundle rb = dynamic.getResourceBundle(formClass.getClassLoader());
    if (BundleBase.SHOW_LOCALIZED_MESSAGES) {
      return new ResourceBundle() {
        @Override
        protected Object handleGetObject(@NotNull String key) {
          Object get = rb.getObject(key);
          assert get instanceof String : "Language bundles should contain only strings";
          return BundleBase.appendLocalizationSuffix((String)get, BundleBase.L10N_MARKER);
        }

        @Override
        public @NotNull Enumeration<String> getKeys() {
          return rb.getKeys();
        }
      };
    }
    else {
      return rb;
    }
  }

  public static void loadLocale(@Nullable LanguageBundleEP langBundle) {
    if (langBundle != null) {
      ourLangTag = langBundle.lang;
      clearGlobalLocaleCache();
      ourBundlesForForms.clear();
    }
  }

  public static @NotNull Locale getLocale() {
    return Locale.forLanguageTag(ourLangTag);
  }
}
