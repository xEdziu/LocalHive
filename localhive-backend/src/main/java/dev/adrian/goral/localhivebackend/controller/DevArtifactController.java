package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.domain.artifact.Artifact;
import dev.adrian.goral.localhivebackend.dto.ArtifactResponseDto;
import dev.adrian.goral.localhivebackend.service.artifact.ArtifactManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Profile("dev")
@RestController
@RequestMapping("/api/dev/artifacts")
@RequiredArgsConstructor
public class DevArtifactController {

    private final ArtifactManagementService artifactManagementService;

    @PostMapping(path = "/workspace-package", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ArtifactResponseDto> uploadWorkspacePackage(@RequestParam("file") MultipartFile file,
                                                                      Authentication authentication) {
        try {
            Artifact artifact = artifactManagementService.storeWorkspacePackage(file, createdBy(authentication));
            return ResponseEntity.ok(ArtifactResponseDto.from(artifact));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private static String createdBy(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return null;
        }

        return authentication.getName();
    }
}
