package com.auraboot.framework.plugin.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ModelFieldRenameMappingPathTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper();

    @Test void explicitMappingInsideTheParsedDirectoryIsRead() throws Exception {
        Path root=Files.createDirectory(directory.resolve("plugin"));
        Path mapping=root.resolve("mapping.json");Files.writeString(mapping,"{\"identifiers\":[]}");
        assertThat(ModelFieldRenameMigrator.readMapping(mapping.toString(),root,json).has("identifiers")).isTrue();
    }
    @Test void theExistingSiblingMigrationsStoreRemainsBoundToTheParsedRepository() throws Exception {
        Path repo=Files.createDirectory(directory.resolve("repo"));Path root=Files.createDirectory(repo.resolve("plugin"));
        Path mapping=Files.createDirectory(repo.resolve("migrations")).resolve("mapping.json");
        Files.writeString(mapping,"{\"identifiers\":[]}");
        assertThat(ModelFieldRenameMigrator.readMapping(mapping.toString(),root,json).has("identifiers")).isTrue();
    }
    @Test void siblingPrefixAndRelativeTraversalCannotExpandTheImportRoot() throws Exception {
        Path root=Files.createDirectory(directory.resolve("plugin"));
        Path sibling=Files.createDirectory(directory.resolve("plugin-other")).resolve("mapping.json");
        Files.writeString(sibling,"{\"identifiers\":[]}");
        assertThatThrownBy(()->ModelFieldRenameMigrator.readMapping(sibling.toString(),root,json)).hasMessageContaining("outside");
        assertThatThrownBy(()->ModelFieldRenameMigrator.readMapping(root.resolve("../plugin-other/mapping.json").toString(),root,json)).hasMessageContaining("traversal");
        assertThatThrownBy(()->ModelFieldRenameMigrator.readMapping("mapping.json",root,json)).hasMessageContaining("outside");
    }
    @Test void symbolicParentCannotRedirectARegularFileOutsideTheImport() throws Exception {
        Path root=Files.createDirectory(directory.resolve("plugin"));
        Path outside=Files.createDirectory(directory.resolve("outside"));Files.writeString(outside.resolve("mapping.json"),"{}");
        Files.createSymbolicLink(root.resolve("linked"),outside);
        assertThatThrownBy(()->ModelFieldRenameMigrator.readMapping(root.resolve("linked/mapping.json").toString(),root,json)).hasMessageContaining("outside");
    }
    @Test void missingRootDirectoryInputsOversizedAndDuplicateMappingsAreRejected() throws Exception {
        assertThatThrownBy(()->ModelFieldRenameMigrator.readMapping("mapping.json",null,json)).hasMessageContaining("directory import");
        assertThatThrownBy(()->ModelFieldRenameMigrator.readMapping(directory.toString(),directory,json)).hasMessageContaining("regular file");
        Path mapping=directory.resolve("mapping.json");Files.writeString(mapping," ".repeat(1_048_577));
        assertThatThrownBy(()->ModelFieldRenameMigrator.readMapping(mapping.toString(),directory,json)).hasMessageContaining("too large");
        Files.writeString(mapping,"{\"schemaVersion\":2,\"schemaVersion\":1}");
        assertThatThrownBy(()->ModelFieldRenameMigrator.readMapping(mapping.toString(),directory,json)).hasMessage("Unreadable identifier mapping");
    }
}
