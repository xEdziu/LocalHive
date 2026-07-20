package dev.adrian.goral.localhivebackend.service.work;

import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAssignmentRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkerSelectionServiceTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.parse("2026-07-20T10:00:00");

    private final WorkerRepository workerRepository = mock(WorkerRepository.class);
    private final ExecutionAssignmentRepository assignmentRepository = mock(ExecutionAssignmentRepository.class);
    private final WorkerSelectionService selectionService = new WorkerSelectionService(
            workerRepository,
            assignmentRepository
    );

    @Test
    void shouldRejectDockerResourceFitWhenSharedRamIsNullOrZero() {
        ResourceRequest dockerResources = ResourceRequest.of(128, 1, false);

        assertThat(selectionService.evaluateEligibility(worker(1, null, 4), dockerResources))
                .isEqualTo(new WorkerSelectionService.WorkerEligibilityResult(
                        false,
                        WorkerSelectionService.WorkerRejectionReason.INSUFFICIENT_MEMORY
                ));
        assertThat(selectionService.evaluateEligibility(worker(2, 0, 4), dockerResources))
                .isEqualTo(new WorkerSelectionService.WorkerEligibilityResult(
                        false,
                        WorkerSelectionService.WorkerRejectionReason.INSUFFICIENT_MEMORY
                ));
    }

    @Test
    void shouldTreatNoOpResourcesAsAlwaysFitting() {
        assertThat(selectionService.evaluateEligibility(worker(1, null, null), ResourceRequest.zero()).eligible())
                .isTrue();
        assertThat(selectionService.evaluateEligibility(worker(2, 0, 0), ResourceRequest.zero()).eligible())
                .isTrue();
    }

    @Test
    void shouldRejectInsufficientCpuAndUnsupportedGpu() {
        assertThat(selectionService.evaluateEligibility(worker(1, 1024, 0), ResourceRequest.of(128, 1, false)))
                .isEqualTo(new WorkerSelectionService.WorkerEligibilityResult(
                        false,
                        WorkerSelectionService.WorkerRejectionReason.INSUFFICIENT_CPU
                ));
        assertThat(selectionService.evaluateEligibility(worker(2, 1024, 4), ResourceRequest.of(128, 1, true)))
                .isEqualTo(new WorkerSelectionService.WorkerEligibilityResult(
                        false,
                        WorkerSelectionService.WorkerRejectionReason.GPU_REQUIRED_UNSUPPORTED
                ));
    }

    @Test
    void shouldSelectHighestMemoryHeadroomFirst() {
        Worker lowerMemory = worker(1, 1024, 8);
        Worker higherMemory = worker(2, 2048, 1);
        mockCandidates(lowerMemory, higherMemory);

        Worker selected = selectionService.selectAuto(ResourceRequest.of(128, 1, false));

        assertThat(selected).isSameAs(higherMemory);
    }

    @Test
    void shouldSelectHighestCpuHeadroomWhenMemoryTies() {
        Worker lowerCpu = worker(1, 1024, 2);
        Worker higherCpu = worker(2, 1024, 8);
        mockCandidates(lowerCpu, higherCpu);

        Worker selected = selectionService.selectAuto(ResourceRequest.of(128, 1, false));

        assertThat(selected).isSameAs(higherCpu);
    }

    @Test
    void shouldPreferWorkerWithNoPriorAssignmentsWhenResourcesTie() {
        Worker previouslyAssigned = worker(1, 1024, 4);
        Worker neverAssigned = worker(2, 1024, 4);
        mockCandidates(
                List.of(latest(previouslyAssigned.getId(), BASE_TIME)),
                previouslyAssigned,
                neverAssigned
        );

        Worker selected = selectionService.selectAuto(ResourceRequest.of(128, 1, false));

        assertThat(selected).isSameAs(neverAssigned);
    }

    @Test
    void shouldSelectLeastRecentlyAssignedWorkerWhenBothHaveHistory() {
        Worker olderAssignment = worker(1, 1024, 4);
        Worker newerAssignment = worker(2, 1024, 4);
        mockCandidates(
                List.of(
                        latest(olderAssignment.getId(), BASE_TIME.minusMinutes(10)),
                        latest(newerAssignment.getId(), BASE_TIME.minusMinutes(1))
                ),
                olderAssignment,
                newerAssignment
        );

        Worker selected = selectionService.selectAuto(ResourceRequest.of(128, 1, false));

        assertThat(selected).isSameAs(olderAssignment);
    }

    @Test
    void shouldUseWorkerIdAscendingAsFinalTieBreaker() {
        Worker smallerId = worker(1, 1024, 4);
        Worker largerId = worker(2, 1024, 4);
        mockCandidates(largerId, smallerId);

        Worker selected = selectionService.selectAuto(ResourceRequest.of(128, 1, false));

        assertThat(selected).isSameAs(smallerId);
    }

    private void mockCandidates(Worker... workers) {
        mockCandidates(List.of(), workers);
    }

    private void mockCandidates(List<ExecutionAssignmentRepository.LatestWorkerAssignment> latestAssignments,
                                Worker... workers) {
        when(workerRepository.findWorkerSelectionCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(workers));
        when(assignmentRepository.findLatestAssignedAtByWorkerIds(any())).thenReturn(latestAssignments);
    }

    private static ExecutionAssignmentRepository.LatestWorkerAssignment latest(UUID workerId,
                                                                              LocalDateTime latestAssignedAt) {
        return new ExecutionAssignmentRepository.LatestWorkerAssignment() {
            @Override
            public UUID getWorkerId() {
                return workerId;
            }

            @Override
            public LocalDateTime getLatestAssignedAt() {
                return latestAssignedAt;
            }
        };
    }

    private static Worker worker(int id, Integer sharedRamMb, Integer cpuCores) {
        return worker(
                id,
                sharedRamMb,
                cpuCores,
                WorkerApprovalStatus.APPROVED,
                WorkerConnectionStatus.ONLINE,
                WorkerAvailabilityStatus.AVAILABLE
        );
    }

    private static Worker worker(int id,
                                 Integer sharedRamMb,
                                 Integer cpuCores,
                                 WorkerApprovalStatus approvalStatus,
                                 WorkerConnectionStatus connectionStatus,
                                 WorkerAvailabilityStatus availabilityStatus) {
        return Worker.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-%012d".formatted(id)))
                .hostname("worker-" + id)
                .ipAddress("192.168.1." + id)
                .osType("Linux")
                .totalRamMb(32768)
                .sharedRamMb(sharedRamMb)
                .cpuCores(cpuCores)
                .approvalStatus(approvalStatus)
                .connectionStatus(connectionStatus)
                .availabilityStatus(availabilityStatus)
                .build();
    }
}
