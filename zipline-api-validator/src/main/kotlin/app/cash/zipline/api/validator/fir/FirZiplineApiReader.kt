/*
 * Copyright (C) 2023 Cash App
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.zipline.api.validator.fir

import java.io.File
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.isSupertypeOf
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.processAllDeclarations
import org.jetbrains.kotlin.fir.declarations.utils.isInterface
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.pipeline.FirResult
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirStarProjection
import org.jetbrains.kotlin.fir.types.FirTypeProjection
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.types.abbreviatedTypeOrSelf
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.lookupTagIfAny
import org.jetbrains.kotlin.fir.visitors.FirDefaultVisitor
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance

fun readFirZiplineApi(
  javaHome: File,
  jdkRelease: Int,
  sources: Collection<File>,
  classpath: Collection<File>,
): FirZiplineApi {
  return KotlinFirLoader(javaHome, jdkRelease, sources, classpath).use { loader ->
    val output = loader.load("zipline-api-dump")
    FirZiplineApiReader(output).read()
  }
}

private val ziplineServiceClassId =
  ClassId(FqName("app.cash.zipline"), Name.identifier("ZiplineService"))

private val autoCloseableClassId =
  ClassId(FqName("java.lang"), Name.identifier("AutoCloseable"))

/**
 * Read the frontend intermediate representation of a program and emit its ZiplineService
 * interfaces. These are subject to strict API compatibility requirements.
 */
internal class FirZiplineApiReader(
  output: FirResult,
) {
  private val platformOutput = output.outputs.first()
  private val session: FirSession = platformOutput.session

  private val ziplineServiceClass: FirClassLikeSymbol<*>? =
    session.symbolProvider.getClassLikeSymbolByClassId(ziplineServiceClassId)

  fun read(): FirZiplineApi {
    val types = platformOutput.fir
      .flatMap { it.regularClasses() }
      .filter { it.isInterface && it.isZiplineService }

    val services = types
      .map { it.asDeclaredZiplineService() }
      .sortedBy { it.name }

    return FirZiplineApi(services)
  }

  private val FirRegularClass.isZiplineService: Boolean
    get() {
      val ziplineServiceClssSymbol = ziplineServiceClass as? FirClassSymbol<*> ?: return false
      return ziplineServiceClssSymbol.isSupertypeOf(symbol, session)
    }

  private fun FirRegularClass.asDeclaredZiplineService(): FirZiplineService {
    return FirZiplineService(
      symbol.classId.asSingleFqName().asString(),
      bridgedFunctions(this),
    )
  }

  private fun bridgedFunctions(type: FirRegularClass): List<FirZiplineFunction> {
    val result = sortedSetOf<FirZiplineFunction>(
      { a, b -> a.signature.compareTo(b.signature) },
    )

    for (supertype in type.getAllSupertypes(session)) {
      if (!supertype.isInterface) continue // Skip kotlin.Any.
      if (supertype.symbol.classId == autoCloseableClassId) continue // Skip AutoCloseable.

      supertype.processAllDeclarations(session) { symbol ->
        when (symbol) {
          is FirFunctionSymbol -> {
            if (symbol.isNonInterfaceFunction) return@processAllDeclarations
            result += symbol.asDeclaredZiplineFunction()
          }

          is FirPropertySymbol -> {
            result += symbol.asDeclaredZiplineFunction()
          }

          else -> Unit
        }
      }
    }

    return result.toList()
  }

  private val FirFunctionSymbol<*>.isNonInterfaceFunction: Boolean
    get() = name.identifier in NON_INTERFACE_FUNCTION_NAMES

  private fun FirFunctionSymbol<*>.asDeclaredZiplineFunction(): FirZiplineFunction {
    val signature = buildString {
      if (isSuspend) append("suspend ")
      append("fun ${name.identifier}(")
      valueParameterSymbols.joinTo(this) { it.resolvedReturnTypeRef.asString() }
      append("): ${resolvedReturnTypeRef.asString()}")
    }

    return FirZiplineFunction(signature)
  }

  private fun FirPropertySymbol.asDeclaredZiplineFunction(): FirZiplineFunction {
    val valOrVar = if (isVar) "var" else "val"
    val signature = "$valOrVar ${name.identifier}: ${resolvedReturnTypeRef.asString()}"
    return FirZiplineFunction(signature)
  }

  /** See [app.cash.zipline.kotlin.asString]. */
  private fun FirTypeRef.asString(): String {
    // Abbreviated type gets us the name of typealiases rather than what they expand to.
    val classLikeSymbol = coneType.abbreviatedTypeOrSelf.lookupTagIfAny
      ?.toClassLikeSymbol(session) ?: error("unexpected class: $this")

    val typeRef = when (this) {
      is FirResolvedTypeRef -> delegatedTypeRef ?: this
      else -> this
    }

    return buildString {
      append(classLikeSymbol.classId.asSingleFqName().asString())

      if (typeRef is FirUserTypeRef) {
        val typeArguments = typeRef.qualifier.lastOrNull()?.typeArgumentList?.typeArguments
        if (typeArguments?.isEmpty() == false) {
          typeArguments.joinTo(this, separator = ",", prefix = "<", postfix = ">") {
            it.asString()
          }
        }
      }
    }
  }

  private fun FirTypeProjection.asString(): String {
    return when (this) {
      is FirStarProjection -> {
        "*"
      }
      is FirTypeProjectionWithVariance -> {
        variance.label + (if (variance != Variance.INVARIANT) " " else "") + typeRef.asString()
      }
      else -> {
        error("Unexpected kind of FirTypeProjection: " + javaClass.simpleName)
      }
    }
  }

  /** Collect all regular class declarations in this. */
  private fun FirFile.regularClasses(): List<FirRegularClass> {
    val result = mutableListOf<FirRegularClass>()
    accept(
      visitor = object : FirDefaultVisitor<Unit, MutableList<FirRegularClass>>() {
        override fun visitRegularClass(
          regularClass: FirRegularClass,
          data: MutableList<FirRegularClass>
        ) {
          super.visitRegularClass(regularClass, data)
          data.add(regularClass)
        }

        override fun visitElement(
          element: FirElement,
          data: MutableList<FirRegularClass>
        ) {
          element.acceptChildren(this, data)
        }
      },
      data = result,
    )
    return result
  }
}
