package dev.dootah.contract

/**
 * Reads and writes the two files the publish check compares.
 *
 * The installed contract is committed alongside the app it describes, so it is
 * written canonically: sorted, fixed field order, LF endings, and no absolute
 * paths. The same source has to produce the same bytes, or the file shows up as
 * changed on every build and stops being read.
 */
public object ContractJson {

    public fun write(contract: InstalledContract): String =
        Json.Obj(
            linkedMapOf(
                "schemaVersion" to Json.Num(contract.schemaVersion),
                "runtimeVersion" to Json.Str(contract.runtimeVersion),
                "screens" to Json.Arr(
                    contract.screens.sortedBy { screen -> screen.id }.map { screen ->
                        Json.Obj(
                            linkedMapOf(
                                "id" to Json.Str(screen.id),
                                "adapters" to Json.Arr(
                                    screen.adapters.sortedBy { it.id }.map { adapter ->
                                        Json.Obj(
                                            linkedMapOf(
                                                "id" to Json.Str(adapter.id),
                                                "props" to adapter.supportedProps.sorted().strings(),
                                            )
                                        )
                                    }
                                ),
                                "capabilities" to Json.Arr(
                                    screen.capabilities.sortedBy { it.id }.map { capability ->
                                        Json.Obj(
                                            linkedMapOf(
                                                "id" to Json.Str(capability.id),
                                                "arity" to Json.Num(capability.arity),
                                            )
                                        )
                                    }
                                ),
                                "handles" to screen.handles.sorted().strings(),
                                "resources" to screen.resources.sorted().strings(),
                            )
                        )
                    }
                ),
            )
        ).render() + "\n"

    public fun readContract(text: String): InstalledContract {

        val root = parseJson(text).obj()

        return InstalledContract(
            schemaVersion = root.number("schemaVersion"),
            runtimeVersion = root.text("runtimeVersion"),
            screens = root.array("screens").map { screen ->
                val fields = screen.obj()
                ScreenContract(
                    id = fields.text("id"),
                    adapters = fields.array("adapters").map { adapter ->
                        val entry = adapter.obj()
                        AdapterContract(
                            id = entry.text("id"),
                            supportedProps = entry.strings("props"),
                        )
                    },
                    capabilities = fields.array("capabilities").map { capability ->
                        val entry = capability.obj()
                        CapabilityContract(
                            id = entry.text("id"),
                            arity = entry.number("arity"),
                        )
                    },
                    handles = fields.strings("handles"),
                    resources = fields.strings("resources"),
                )
            },
        )
    }

    public fun write(requirements: BundleRequirements): String =
        Json.Obj(
            linkedMapOf(
                "schemaVersion" to Json.Num(InstalledContract.SCHEMA_VERSION),
                "runtimeVersion" to Json.Str(requirements.runtimeVersion),
                "screens" to Json.Arr(
                    requirements.screens.sortedBy { screen -> screen.id }.map { screen ->
                        Json.Obj(
                            linkedMapOf(
                                "id" to Json.Str(screen.id),
                                "adapters" to Json.Arr(
                                    screen.adapters.sortedBy { it.id }.map { use ->
                                        Json.Obj(
                                            linkedMapOf(
                                                "id" to Json.Str(use.id),
                                                "props" to use.props.sorted().strings(),
                                            )
                                        )
                                    }
                                ),
                                "capabilities" to Json.Arr(
                                    screen.capabilities.sortedBy { it.id }.map { capability ->
                                        Json.Obj(
                                            linkedMapOf(
                                                "id" to Json.Str(capability.id),
                                                "arity" to Json.Num(capability.arity),
                                            )
                                        )
                                    }
                                ),
                                "handles" to screen.handles.sorted().strings(),
                                "resources" to screen.resources.sorted().strings(),
                            )
                        )
                    }
                ),
            )
        ).render() + "\n"

    public fun readRequirements(text: String): BundleRequirements {

        val root = parseJson(text).obj()

        return BundleRequirements(
            runtimeVersion = root.text("runtimeVersion"),
            screens = root.array("screens").map { screen ->
                val fields = screen.obj()
                ScreenRequirements(
                    id = fields.text("id"),
                    adapters = fields.array("adapters").map { adapter ->
                        val entry = adapter.obj()
                        AdapterUse(id = entry.text("id"), props = entry.strings("props"))
                    },
                    capabilities = fields.array("capabilities").map { capability ->
                        val entry = capability.obj()
                        CapabilityContract(
                            id = entry.text("id"),
                            arity = entry.number("arity"),
                        )
                    },
                    handles = fields.strings("handles"),
                    resources = fields.strings("resources"),
                )
            },
        )
    }

    private fun List<String>.strings(): Json = Json.Arr(map { value -> Json.Str(value) })

    private fun Json.obj(): Json.Obj =
        this as? Json.Obj ?: throw JsonException("expected an object")

    private fun Json.Obj.field(name: String): Json =
        fields[name] ?: throw JsonException("missing '$name'")

    private fun Json.Obj.text(name: String): String =
        (field(name) as? Json.Str)?.value ?: throw JsonException("'$name' is not text")

    private fun Json.Obj.number(name: String): Int =
        (field(name) as? Json.Num)?.value ?: throw JsonException("'$name' is not a number")

    private fun Json.Obj.array(name: String): List<Json> =
        (field(name) as? Json.Arr)?.items ?: throw JsonException("'$name' is not a list")

    private fun Json.Obj.strings(name: String): List<String> =
        array(name).map { item ->
            (item as? Json.Str)?.value ?: throw JsonException("'$name' holds something that is not text")
        }
}
