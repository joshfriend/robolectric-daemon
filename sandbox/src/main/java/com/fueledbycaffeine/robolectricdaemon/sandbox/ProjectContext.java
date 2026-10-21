package com.fueledbycaffeine.robolectricdaemon.sandbox;

/** Shared by the sandbox lifecycle plugin and its parent-loaded class loader. */
public interface ProjectContext {
  ClassLoader projectContext();
}
