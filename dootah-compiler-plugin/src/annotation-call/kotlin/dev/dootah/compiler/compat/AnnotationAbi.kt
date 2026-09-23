package dev.dootah.compiler.compat

// Older IR represents annotations as constructor calls.
typealias IrAnnotation = org.jetbrains.kotlin.ir.expressions.IrConstructorCall
