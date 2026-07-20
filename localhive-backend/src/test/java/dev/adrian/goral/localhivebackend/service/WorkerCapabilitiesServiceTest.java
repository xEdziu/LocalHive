package dev.adrian.goral.localhivebackend.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.WorkerCapabilities;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.dto.WorkerCapabilitiesDto;
import dev.adrian.goral.localhivebackend.repository.WorkerCapabilitiesRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerCapabilitiesServiceTest {

    private final WorkerCapabilitiesRepository repository = mock(WorkerCapabilitiesRepository.class);
    private final WorkerCapabilitiesService service = new WorkerCapabilitiesService(repository);

    @Test
    void shouldPersistValidCapabilitySnapshot() {
        Worker worker = worker();
        LocalDateTime reportedAt = LocalDateTime.parse("2026-07-20T12:00:00");
        when(repository.findById(worker.getId())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var saved = service.replaceCapabilities(worker, validCapabilities(), reportedAt);

        assertThat(saved.getWorkerId()).isEqualTo(worker.getId());
        assertThat(saved.getReportedAt()).isEqualTo(reportedAt);
        assertThat(saved.getExecutors().size()).isEqualTo(2);
        assertThat(saved.getExecutors().get(0).get("executorId").asText()).isEqualTo("localhive.no-op");
        assertThat(saved.getExecutors().get(0).get("executorContractVersion").asInt()).isEqualTo(1);
        assertThat(saved.getExecutors().get(0).get("enabled").asBoolean()).isTrue();
        assertThat(saved.getDockerEnabled()).isTrue();
        assertThat(saved.getDockerAllowedImages().size()).isEqualTo(1);
        assertThat(saved.getDockerAllowedImages().get(0).asText()).isEqualTo("alpine:3.20");
        assertThat(saved.getDockerMaxMemoryMb()).isEqualTo(4096);
        assertThat(saved.getDockerMaxCpuCores()).isEqualTo(8);
        assertThat(saved.getDockerGpuAllowed()).isFalse();
    }

    @Test
    void shouldReplaceExistingCapabilitySnapshot() {
        Worker worker = worker();
        LocalDateTime reportedAt = LocalDateTime.parse("2026-07-20T12:30:00");
        WorkerCapabilities existing = WorkerCapabilities.create(worker);
        existing.replaceWith(
                LocalDateTime.parse("2026-07-20T12:00:00"),
                JsonNodeFactory.instance.arrayNode(),
                true,
                JsonNodeFactory.instance.arrayNode(),
                4096,
                8,
                false
        );

        when(repository.findById(worker.getId())).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var saved = service.replaceCapabilities(
                worker,
                new WorkerCapabilitiesDto(
                        List.of(new WorkerCapabilitiesDto.ExecutorCapabilityDto("localhive.no-op", 1, true)),
                        new WorkerCapabilitiesDto.DockerCapabilityDto(
                                false,
                                List.of("busybox:1.36"),
                                1024,
                                1,
                                false
                        )
                ),
                reportedAt
        );

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getReportedAt()).isEqualTo(reportedAt);
        assertThat(saved.getExecutors().size()).isEqualTo(1);
        assertThat(saved.getDockerEnabled()).isFalse();
        assertThat(saved.getDockerAllowedImages().get(0).asText()).isEqualTo("busybox:1.36");
        assertThat(saved.getDockerMaxMemoryMb()).isEqualTo(1024);
        assertThat(saved.getDockerMaxCpuCores()).isEqualTo(1);
    }

    @Test
    void shouldRejectBlankExecutorIdBeforeSaving() {
        assertInvalidCapabilitiesAreRejected(new WorkerCapabilitiesDto(
                List.of(new WorkerCapabilitiesDto.ExecutorCapabilityDto("   ", 1, true)),
                null
        ));
    }

    @Test
    void shouldRejectTooManyExecutorsBeforeSaving() {
        List<WorkerCapabilitiesDto.ExecutorCapabilityDto> executors = IntStream.rangeClosed(1, 51)
                .mapToObj(index -> new WorkerCapabilitiesDto.ExecutorCapabilityDto(
                        "localhive.executor-" + index,
                        1,
                        true
                ))
                .toList();

        assertInvalidCapabilitiesAreRejected(new WorkerCapabilitiesDto(executors, null));
    }

    @Test
    void shouldRejectNegativeDockerMemoryBeforeSaving() {
        assertInvalidCapabilitiesAreRejected(new WorkerCapabilitiesDto(
                List.of(new WorkerCapabilitiesDto.ExecutorCapabilityDto("localhive.no-op", 1, true)),
                new WorkerCapabilitiesDto.DockerCapabilityDto(
                        true,
                        List.of("alpine:3.20"),
                        -1,
                        8,
                        false
                )
        ));
    }

    @Test
    void shouldRejectTooManyAllowedImagesBeforeSaving() {
        List<String> allowedImages = IntStream.rangeClosed(1, 101)
                .mapToObj(index -> "localhive/image-" + index + ":latest")
                .toList();

        assertInvalidCapabilitiesAreRejected(new WorkerCapabilitiesDto(
                List.of(new WorkerCapabilitiesDto.ExecutorCapabilityDto("localhive.no-op", 1, true)),
                new WorkerCapabilitiesDto.DockerCapabilityDto(
                        true,
                        allowedImages,
                        4096,
                        8,
                        false
                )
        ));
    }

    private void assertInvalidCapabilitiesAreRejected(WorkerCapabilitiesDto capabilities) {
        assertThatThrownBy(() -> service.replaceCapabilities(worker(), capabilities, LocalDateTime.now()))
                .isInstanceOf(IllegalStateException.class);

        verify(repository, never()).findById(any());
        verify(repository, never()).save(any());
    }

    private static WorkerCapabilitiesDto validCapabilities() {
        return new WorkerCapabilitiesDto(
                List.of(
                        new WorkerCapabilitiesDto.ExecutorCapabilityDto("localhive.no-op", 1, true),
                        new WorkerCapabilitiesDto.ExecutorCapabilityDto("localhive.docker.workload", 1, true)
                ),
                new WorkerCapabilitiesDto.DockerCapabilityDto(
                        true,
                        List.of("alpine:3.20"),
                        4096,
                        8,
                        false
                )
        );
    }

    private static Worker worker() {
        UUID workerId = UUID.randomUUID();
        return Worker.builder()
                .id(workerId)
                .hostname("worker-" + workerId)
                .ipAddress("192.168.1.10")
                .osType("Linux")
                .totalRamMb(32768)
                .sharedRamMb(8192)
                .cpuCores(16)
                .gpuName("RTX 5080")
                .approvalStatus(WorkerApprovalStatus.APPROVED)
                .connectionStatus(WorkerConnectionStatus.ONLINE)
                .availabilityStatus(WorkerAvailabilityStatus.AVAILABLE)
                .build();
    }
}
