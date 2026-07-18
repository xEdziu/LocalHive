package dev.adrian.goral.localhivebackend.service.artifact;

import dev.adrian.goral.localhivebackend.repository.artifact.ArtifactRepository;
import dev.adrian.goral.localhivebackend.service.work.ExecutionLeaseValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArtifactManagementServiceTest {

    @Mock
    private ArtifactRepository artifactRepository;

    @Mock
    private ArtifactStorageService storageService;

    @Mock
    private ExecutionLeaseValidationService leaseValidationService;

    @InjectMocks
    private ArtifactManagementService artifactManagementService;

    @Test
    void shouldRejectOversizedWorkspacePackageBeforeStorage() {
        MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("workspace.zip");
        when(file.getContentType()).thenReturn("application/zip");
        when(file.getSize()).thenReturn(ArtifactManagementService.MAX_WORKSPACE_PACKAGE_SIZE_BYTES + 1);

        assertThatThrownBy(() -> artifactManagementService.storeWorkspacePackage(file, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("file must be at most 50 MB.");

        verifyNoInteractions(storageService);
        verifyNoInteractions(artifactRepository);
    }
}
