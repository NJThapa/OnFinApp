package com.onfinapp.api.llm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** A prompt and the JSON schema of its answer, read from the shared llm folder. */
public record PromptTemplate(String text, String schema) {

    public static PromptTemplate load(String sharedDir, String name) {
        Path folder = Path.of(sharedDir, "llm");
        try {
            return new PromptTemplate(
                    Files.readString(folder.resolve(name + ".prompt.md")),
                    Files.readString(folder.resolve(name + ".schema.json")));
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot read the " + name + " prompt and schema in " + folder.toAbsolutePath() + ".", e);
        }
    }

    /** The prompt with every {{key}} replaced by its value. */
    public String render(Map<String, String> variables) {
        String rendered = text;
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            rendered = rendered.replace("{{" + variable.getKey() + "}}", variable.getValue());
        }
        return rendered;
    }
}
