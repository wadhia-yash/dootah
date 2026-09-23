package dev.dootah.compiler.compat

// Newer IR distinguishes annotation nodes from ordinary constructor calls.
typealias IrAnnotation = org.jetbrains.kotlin.ir.expressions.IrAnnotation
