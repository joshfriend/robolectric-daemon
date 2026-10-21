package com.fueledbycaffeine.robolectricdaemon

import org.gradle.api.Describable
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

internal abstract class CiValueSource :
  ValueSource<Boolean, CiValueSource.Parameters>, Describable {
  interface Parameters : ValueSourceParameters {
    val environmentVariable: Property<String>
    val systemProperty: Property<String>
  }

  override fun getDisplayName(): String = "the CI status"

  override fun obtain(): Boolean =
    parameters.environmentVariable.isPresent || parameters.systemProperty.isPresent
}
