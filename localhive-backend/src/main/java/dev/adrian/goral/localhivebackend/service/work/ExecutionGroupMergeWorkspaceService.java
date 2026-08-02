package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.adrian.goral.localhivebackend.domain.artifact.Artifact;
import dev.adrian.goral.localhivebackend.domain.artifact.ArtifactKind;
import dev.adrian.goral.localhivebackend.domain.artifact.ExecutionArtifact;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionGroup;
import dev.adrian.goral.localhivebackend.domain.work.ExecutionGroupMergePlan;
import dev.adrian.goral.localhivebackend.domain.work.WorkExecution;
import dev.adrian.goral.localhivebackend.repository.artifact.ArtifactRepository;
import dev.adrian.goral.localhivebackend.repository.artifact.ExecutionArtifactRepository;
import dev.adrian.goral.localhivebackend.service.artifact.ArtifactManagementService;
import dev.adrian.goral.localhivebackend.service.artifact.ArtifactStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class ExecutionGroupMergeWorkspaceService {

    static final int MAX_DERIVED_WORKSPACE_ENTRIES = 1000;

    private static final String INPUT_MANIFEST_ENTRY = "inputs/manifest.json";
    private static final String INPUT_MANIFEST_PATH = "/workspace/" + INPUT_MANIFEST_ENTRY;
    private static final Pattern WINDOWS_DRIVE_PATH = Pattern.compile("^[A-Za-z]:.*");

    private final ObjectMapper objectMapper;
    private final ArtifactRepository artifactRepository;
    private final ExecutionArtifactRepository executionArtifactRepository;
    private final ArtifactStorageService storageService;
    private final ArtifactManagementService artifactManagementService;

    public Artifact createDerivedWorkspacePackage(ExecutionGroup group,
                                                  ExecutionGroupMergePlan mergePlan,
                                                  List<WorkExecution> successfulShards) {
        ExecutionGroup validGroup = Objects.requireNonNull(group, "group must not be null.");
        ExecutionGroupMergePlan validMergePlan = Objects.requireNonNull(
                mergePlan,
                "mergePlan must not be null."
        );
        List<WorkExecution> includedShards = requireSuccessfulShards(successfulShards);
        Artifact baseWorkspaceArtifact = resolveBaseWorkspaceArtifact(validMergePlan);

        byte[] packageContent = buildPackage(validGroup, baseWorkspaceArtifact, includedShards);
        return artifactManagementService.storeGeneratedWorkspacePackage(
                packageContent,
                "merge-workspace-" + validGroup.getId() + ".zip",
                "execution-group:" + validGroup.getId()
        );
    }

    private byte[] buildPackage(ExecutionGroup group,
                                Artifact baseWorkspaceArtifact,
                                List<WorkExecution> successfulShards) {
        Set<String> entries = new HashSet<>();
        EntryCounter entryCounter = new EntryCounter();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (ZipOutputStream zipOutput = new ZipOutputStream(output)) {
            copyBaseWorkspace(baseWorkspaceArtifact, zipOutput, entries, entryCounter);
            ObjectNode manifest = manifest(group, successfulShards, zipOutput, entries, entryCounter);
            addBytesEntry(
                    zipOutput,
                    entries,
                    entryCounter,
                    INPUT_MANIFEST_ENTRY,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest)
            );
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to build merge workspace package.", e);
        }

        return output.toByteArray();
    }

    private void copyBaseWorkspace(Artifact baseWorkspaceArtifact,
                                   ZipOutputStream zipOutput,
                                   Set<String> entries,
                                   EntryCounter entryCounter) throws IOException {
        Path baseWorkspacePath = storageService.resolveReadablePath(baseWorkspaceArtifact);
        try (ZipInputStream zipInput = new ZipInputStream(Files.newInputStream(baseWorkspacePath))) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                boolean directory = entry.isDirectory() || entry.getName().replace('\\', '/').endsWith("/");
                String entryName = normalizeArchiveEntryName(entry.getName(), directory);
                if (entryName.equals("inputs/") || entryName.startsWith("inputs/")) {
                    throw new IllegalArgumentException("base merge workspace must not contain inputs/ entries.");
                }
                addStreamEntry(zipOutput, entries, entryCounter, entryName, directory, zipInput);
                zipInput.closeEntry();
            }
        }
    }

    private ObjectNode manifest(ExecutionGroup group,
                                List<WorkExecution> successfulShards,
                                ZipOutputStream zipOutput,
                                Set<String> entries,
                                EntryCounter entryCounter) throws IOException {
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("executionGroupId", group.getId().toString());
        manifest.put("shardCount", group.getShardCount());
        manifest.put("includedShardCount", successfulShards.size());
        manifest.put("failurePolicy", group.getFailurePolicy().name());

        ArrayNode shards = manifest.putArray("shards");
        for (WorkExecution shard : successfulShards.stream()
                .sorted(Comparator.comparing(WorkExecution::getShardIndex))
                .toList()) {
            ObjectNode shardNode = shards.addObject();
            shardNode.put("executionId", shard.getId().toString());
            shardNode.put("shardIndex", shard.getShardIndex());
            shardNode.put("status", shard.getStatus().name());
            shardNode.put("inputDirectory", "/workspace/inputs/shards/" + shard.getShardIndex());

            ArrayNode artifacts = shardNode.putArray("artifacts");
            for (ExecutionArtifact executionArtifact : executionArtifactRepository
                    .findByExecution_IdAndArtifact_KindOrderByCreatedAtAsc(
                            shard.getId(),
                            ArtifactKind.EXECUTION_OUTPUT
                    )) {
                String relativePath = normalizeArchiveEntryName(executionArtifact.getRelativePath(), false);
                String inputEntry = "inputs/shards/" + shard.getShardIndex() + "/" + relativePath;
                addFileEntry(
                        zipOutput,
                        entries,
                        entryCounter,
                        inputEntry,
                        storageService.resolveReadablePath(executionArtifact.getArtifact())
                );

                ObjectNode artifactNode = artifacts.addObject();
                artifactNode.put("artifactId", executionArtifact.getArtifact().getId().toString());
                artifactNode.put("relativePath", relativePath);
                artifactNode.put("inputPath", "/workspace/" + inputEntry);
            }
        }

        return manifest;
    }

    private Artifact resolveBaseWorkspaceArtifact(ExecutionGroupMergePlan mergePlan) {
        String artifactId = mergePlan.getConfigurationTemplate()
                .path("workspace")
                .path("artifactId")
                .asText(null);
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("mergeConfigurationTemplate.workspace.artifactId is required.");
        }

        UUID workspaceArtifactId;
        try {
            workspaceArtifactId = UUID.fromString(artifactId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mergeConfigurationTemplate.workspace.artifactId must be a valid UUID.", e);
        }

        return artifactRepository.findById(workspaceArtifactId)
                .filter(artifact -> artifact.getKind() == ArtifactKind.WORKSPACE_PACKAGE)
                .orElseThrow(() -> new IllegalArgumentException(
                        "mergeConfigurationTemplate.workspace.artifactId must reference an existing WORKSPACE_PACKAGE artifact."
                ));
    }

    private static List<WorkExecution> requireSuccessfulShards(List<WorkExecution> successfulShards) {
        if (successfulShards == null || successfulShards.isEmpty()) {
            throw new IllegalArgumentException("At least one successful shard is required for AGENT merge.");
        }

        return List.copyOf(successfulShards);
    }

    private static String normalizeArchiveEntryName(String rawName, boolean directory) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("ZIP entry name must not be blank.");
        }
        if (rawName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("ZIP entry name cannot contain a null byte.");
        }

        String normalizedSeparators = rawName.replace('\\', '/');
        if (normalizedSeparators.startsWith("/") || WINDOWS_DRIVE_PATH.matcher(normalizedSeparators).matches()) {
            throw new IllegalArgumentException("ZIP entry name must be relative.");
        }

        String[] segments = normalizedSeparators.split("/", -1);
        StringBuilder normalized = new StringBuilder();
        for (String segment : segments) {
            if (segment.isBlank()) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("ZIP entry name cannot contain parent traversal.");
            }
            if (".".equals(segment)) {
                continue;
            }
            if (!normalized.isEmpty()) {
                normalized.append('/');
            }
            normalized.append(segment);
        }

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("ZIP entry name must not be blank.");
        }

        return directory ? normalized + "/" : normalized.toString();
    }

    private static void addFileEntry(ZipOutputStream zipOutput,
                                     Set<String> entries,
                                     EntryCounter entryCounter,
                                     String entryName,
                                     Path source) throws IOException {
        try (InputStream input = Files.newInputStream(source)) {
            addStreamEntry(zipOutput, entries, entryCounter, entryName, false, input);
        }
    }

    private static void addBytesEntry(ZipOutputStream zipOutput,
                                      Set<String> entries,
                                      EntryCounter entryCounter,
                                      String entryName,
                                      byte[] content) throws IOException {
        addStreamEntry(
                zipOutput,
                entries,
                entryCounter,
                entryName,
                false,
                new java.io.ByteArrayInputStream(content)
        );
    }

    private static void addStreamEntry(ZipOutputStream zipOutput,
                                       Set<String> entries,
                                       EntryCounter entryCounter,
                                       String entryName,
                                       boolean directory,
                                       InputStream input) throws IOException {
        String normalizedEntryName = normalizeArchiveEntryName(entryName, directory);
        if (!entries.add(normalizedEntryName)) {
            throw new IllegalArgumentException("Duplicate ZIP entry: " + normalizedEntryName);
        }
        entryCounter.increment();

        ZipEntry zipEntry = new ZipEntry(normalizedEntryName);
        zipOutput.putNextEntry(zipEntry);
        if (!directory) {
            input.transferTo(zipOutput);
        }
        zipOutput.closeEntry();
    }

    private static final class EntryCounter {

        private int value;

        void increment() {
            value++;
            if (value > MAX_DERIVED_WORKSPACE_ENTRIES) {
                throw new IllegalArgumentException("merge workspace package must contain at most 1000 entries.");
            }
        }
    }
}
