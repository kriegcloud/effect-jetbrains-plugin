package dev.effect.intellij.lsp

import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

/** Keeps raw initialization JSON intact, including object nulls omitted by LSP4J's default Gson. */
@JsonAdapter(LspInitializationOptions.Adapter::class)
internal class LspInitializationOptions(private val json: String) {
    class Adapter : TypeAdapter<LspInitializationOptions>() {
        override fun write(writer: JsonWriter, value: LspInitializationOptions?) {
            writer.jsonValue(value?.json)
        }

        override fun read(reader: JsonReader): LspInitializationOptions =
            LspInitializationOptions(JsonParser.parseReader(reader).toString())
    }
}
