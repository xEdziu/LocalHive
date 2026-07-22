package dev.adrian.goral.localhivebackend.service.work;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.adrian.goral.localhivebackend.domain.Worker;
import dev.adrian.goral.localhivebackend.domain.WorkerCapabilities;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerApprovalStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerAvailabilityStatus;
import dev.adrian.goral.localhivebackend.domain.enums.WorkerConnectionStatus;
import dev.adrian.goral.localhivebackend.domain.work.ResourceRequest;
import dev.adrian.goral.localhivebackend.repository.WorkerCapabilitiesRepository;
import dev.adrian.goral.localhivebackend.repository.WorkerRepository;
import dev.adrian.goral.localhivebackend.repository.work.ExecutionAssignmentRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkerSelectionServiceTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.parse("2026-07-20T10:00:00");
    private static final String NO_OP_EXECUTOR_ID = "localhive.no-op";
    private static final String DOCKER_EXECUTOR_ID = "localhive.docker.workload";
    private static final int EXECUTOR_CONTRACT_VERSION = 1;
    private static final String DOCKER_IMAGE = "alpine:3.20";

    private final WorkerRepository workerRepository = mock(WorkerRepository.class);
    private final ExecutionAssignmentRepository assignmentRepository = mock(ExecutionAssignmentRepository.class);
    private final WorkerCapabilitiesRepository workerCapabilitiesRepository = mock(WorkerCapabilitiesRepository.class);
    private final WorkerSelectionService selectionService = new WorkerSelectionService(
            workerRepository,
            assignmentRepository,
            workerCapabilitiesRepository
    );

    @Test
    void shouldRejectDockerResourceFitWhenSharedRamIsNullOrZero() {
        ResourceRequest dockerResources = ResourceRequest.of(128, 1, false);
        Worker firstWorker = worker(1, null, 4);
        Worker secondWorker = worker(2, 0, 4);

        assertThat(selectionService.evaluateEligibility(
                firstWorker,
                dockerCriteria(dockerResources),
                dockerCapabilities(firstWorker, true, true, List.of(DOCKER_IMAGE), 4096, 8)
        ))
                .isEqualTo(new WorkerSelectionService.WorkerEligibilityResult(
                        false,
                        WorkerSelectionService.WorkerRejectionReason.INSUFFICIENT_MEMORY
                ));
        assertThat(selectionService.evaluateEligibility(
                secondWorker,
                dockerCriteria(dockerResources),
                dockerCapabilities(secondWorker, true, true, List.of(DOCKER_IMAGE), 4096, 8)
        ))
                .isEqualTo(new WorkerSelectionService.WorkerEligibilityResult(
                        false,
                        WorkerSelectionService.WorkerRejectionReason.INSUFFICIENT_MEMORY
                ));
    }

    @Test
    void shouldTreatNoOpResourcesAsAlwaysFitting() {
        Worker firstWorker = worker(1, null, null);
        Worker secondWorker = worker(2, 0, 0);

        assertThat(selectionService.evaluateEligibility(
                firstWorker,
                noOpCriteria(ResourceRequest.zero()),
                noOpCapabilities(firstWorker)
        ).eligible())
                .isTrue();
        assertThat(selectionService.evaluateEligibility(
                secondWorker,
                noOpCriteria(ResourceRequest.zero()),
                noOpCapabilities(secondWorker)
        ).eligible())
                .isTrue();
    }

    @Test
    void shouldRejectInsufficientCpuAndUnsupportedGpu() {
        Worker insufficientCpuWorker = worker(1, 1024, 0);
        Worker gpuWorker = worker(2, 1024, 4);

        assertThat(selectionService.evaluateEligibility(
                insufficientCpuWorker,
                noOpCriteria(ResourceRequest.of(128, 1, false)),
                noOpCapabilities(insufficientCpuWorker)
        ))
                .isEqualTo(new WorkerSelectionService.WorkerEligibilityResult(
                        false,
                        WorkerSelectionService.WorkerRejectionReason.INSUFFICIENT_CPU
                ));
        assertThat(selectionService.evaluateEligibility(
                gpuWorker,
                noOpCriteria(ResourceRequest.of(128, 1, true)),
                noOpCapabilities(gpuWorker)
        ))
                .isEqualTo(new WorkerSelectionService.WorkerEligibilityResult(
                        false,
                        WorkerSelectionService.WorkerRejectionReason.GPU_REQUIRED_UNSUPPORTED
                ));
    }

    @Test
    void shouldRequireMatchingEnabledNoOpCapabilityForAutomaticSelection() {
        Worker missingCapabilities = worker(1, 8192, 8);
        Worker missingExecutor = worker(2, 8192, 8);
        Worker disabledExecutor = worker(3, 8192, 8);
        Worker matchingExecutor = worker(4, 8192, 8);

        assertThat(selectionService.evaluateEligibility(
                missingCapabilities,
                noOpCriteria(ResourceRequest.zero()),
                null
        ))
                .isEqualTo(new WorkerSelectionService.WorkerEligibilityResult(
                        false,
                        WorkerSelectionService.WorkerRejectionReason.MISSING_CAPABILITIES
                ));
        assertThat(selectionService.evaluateEligibility(
                missingExecutor,
                noOpCriteria(ResourceRequest.zero()),
                dockerCapabilities(missingExecutor, true, true, List.of(DOCKER_IMAGE), 4096, 8)
        ))
                .isEqualTo(new WorkerSelectionService.WorkerEligibilityResult(
                        false,
                        WorkerSelectionService.WorkerRejectionReason.EXECUTOR_NOT_SUPPORTED
                ));
        assertThat(selectionService.evaluateEligibility(
                disabledExecutor,
                noOpCriteria(ResourceRequest.zero()),
                noOpCapabilities(disabledExecutor, false)
        ))
                .isEqualTo(new WorkerSelectionService.WorkerEligibilityResult(
                        false,
                        WorkerSelectionService.WorkerRejectionReason.EXECUTOR_DISABLED
                ));
        assertThat(selectionService.evaluateEligibility(
                matchingExecutor,
                noOpCriteria(ResourceRequest.zero()),
                noOpCapabilities(matchingExecutor)
        ).eligible())
                .isTrue();
    }

    @Test
    void shouldApplyDockerCapabilityPolicy() {
        ResourceRequest requestedResources = ResourceRequest.of(128, 1, false);
        Worker worker = worker(1, 1024, 4);

        assertThat(selectionService.evaluateEligibility(
                worker,
                dockerCriteria(requestedResources),
                dockerCapabilities(worker, true, true, List.of(DOCKER_IMAGE), null, null)
        ).eligible())
                .isTrue();
        assertThat(selectionService.evaluateEligibility(
                worker,
                dockerCriteria(requestedResources),
                dockerCapabilities(worker, false, true, List.of(DOCKER_IMAGE), 4096, 8)
        ))
                .isEqualTo(new WorkerSelectionService.WorkerEligibilityResult(
                        false,
                        WorkerSelectionService.WorkerRejectionReason.EXECUTOR_DISABLED
                ));
        assertThat(selectionService.evaluateEligibility(
                worker,
                dockerCriteria(requestedResources),
                dockerCapabilities(worker, true, null, null, null, null)
        ))
                .isEqualTo(new WorkerSelectionService.WorkerEligibilityResult(
                        false,
                        WorkerSelectionService.WorkerRejectionReason.DOCKER_CAPABILITY_MISSING
                ));
        assertThat(selectionService.evaluateEligibility(
                worker,
                dockerCriteria(requestedResources),
                dockerCapabilities(worker, true, true, List.of(), 4096, 8)
        ))
                .isEqualTo(new WorkerSelectionService.WorkerEligibilityResult(
                        false,
                        WorkerSelectionService.WorkerRejectionReason.DOCKER_IMAGE_NOT_ALLOWED
                ));
        assertThat(selectionService.evaluateEligibility(
                worker,
                dockerCriteria(requestedResources),
                dockerCapabilities(worker, true, true, List.of("ubuntu:24.04"), 4096, 8)
        ))
                .isEqualTo(new WorkerSelectionService.WorkerEligibilityResult(
                        false,
                        WorkerSelectionService.WorkerRejectionReason.DOCKER_IMAGE_NOT_ALLOWED
                ));
        assertThat(selectionService.evaluateEligibility(
                worker,
                dockerCriteria(requestedResources),
                dockerCapabilities(worker, true, true, List.of(DOCKER_IMAGE), 127, 8)
        ))
                .isEqualTo(new WorkerSelectionService.WorkerEligibilityResult(
                        false,
                        WorkerSelectionService.WorkerRejectionReason.DOCKER_POLICY_MEMORY_EXCEEDED
                ));
        assertThat(selectionService.evaluateEligibility(
                worker,
                dockerCriteria(requestedResources),
                dockerCapabilities(worker, true, true, List.of(DOCKER_IMAGE), 4096, 0)
        ))
                .isEqualTo(new WorkerSelectionService.WorkerEligibilityResult(
                        false,
                        WorkerSelectionService.WorkerRejectionReason.DOCKER_POLICY_CPU_EXCEEDED
                ));
    }

    @Test
    void shouldSelectHighestMemoryHeadroomFirst() {
        Worker lowerMemory = worker(1, 1024, 8);
        Worker higherMemory = worker(2, 2048, 1);
        mockCandidates(lowerMemory, higherMemory);

        Worker selected = selectionService.selectAuto(noOpCriteria(ResourceRequest.of(128, 1, false)));

        assertThat(selected).isSameAs(higherMemory);
    }

    @Test
    void shouldSelectHighestCpuHeadroomWhenMemoryTies() {
        Worker lowerCpu = worker(1, 1024, 2);
        Worker higherCpu = worker(2, 1024, 8);
        mockCandidates(lowerCpu, higherCpu);

        Worker selected = selectionService.selectAuto(noOpCriteria(ResourceRequest.of(128, 1, false)));

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

        Worker selected = selectionService.selectAuto(noOpCriteria(ResourceRequest.of(128, 1, false)));

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

        Worker selected = selectionService.selectAuto(noOpCriteria(ResourceRequest.of(128, 1, false)));

        assertThat(selected).isSameAs(olderAssignment);
    }

    @Test
    void shouldUseWorkerIdAscendingAsFinalTieBreaker() {
        Worker smallerId = worker(1, 1024, 4);
        Worker largerId = worker(2, 1024, 4);
        mockCandidates(largerId, smallerId);

        Worker selected = selectionService.selectAuto(noOpCriteria(ResourceRequest.of(128, 1, false)));

        assertThat(selected).isSameAs(smallerId);
    }

    @Test
    void shouldFilterCapabilitiesBeforeApplyingExistingScoring() {
        Worker highestScoreWithoutCapabilities = worker(1, 4096, 16);
        Worker lowerScoreWithCapabilities = worker(2, 1024, 2);
        mockCandidatesWithCapabilities(
                List.of(noOpCapabilities(lowerScoreWithCapabilities)),
                List.of(),
                highestScoreWithoutCapabilities,
                lowerScoreWithCapabilities
        );

        Worker selected = selectionService.selectAuto(noOpCriteria(ResourceRequest.zero()));

        assertThat(selected).isSameAs(lowerScoreWithCapabilities);
    }

    private void mockCandidates(Worker... workers) {
        mockCandidates(List.of(), workers);
    }

    private void mockCandidates(List<ExecutionAssignmentRepository.LatestWorkerAssignment> latestAssignments,
                                Worker... workers) {
        mockCandidatesWithCapabilities(
                Arrays.stream(workers).map(WorkerSelectionServiceTest::noOpCapabilities).toList(),
                latestAssignments,
                workers
        );
    }

    private void mockCandidatesWithCapabilities(List<WorkerCapabilities> capabilities,
                                                List<ExecutionAssignmentRepository.LatestWorkerAssignment> latestAssignments,
                                                Worker... workers) {
        when(workerRepository.findWorkerSelectionCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(workers));
        when(assignmentRepository.findLatestAssignedAtByWorkerIds(any())).thenReturn(latestAssignments);
        when(workerCapabilitiesRepository.findAllById(any())).thenReturn(capabilities);
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

    private static WorkerSelectionCriteria noOpCriteria(ResourceRequest requestedResources) {
        return new WorkerSelectionCriteria(NO_OP_EXECUTOR_ID, EXECUTOR_CONTRACT_VERSION, requestedResources, null);
    }

    private static WorkerSelectionCriteria dockerCriteria(ResourceRequest requestedResources) {
        return new WorkerSelectionCriteria(DOCKER_EXECUTOR_ID, EXECUTOR_CONTRACT_VERSION, requestedResources, DOCKER_IMAGE);
    }

    private static WorkerCapabilities noOpCapabilities(Worker worker) {
        return noOpCapabilities(worker, true);
    }

    private static WorkerCapabilities noOpCapabilities(Worker worker, boolean enabled) {
        return capabilities(worker, executors(executor(NO_OP_EXECUTOR_ID, enabled)), null, null, null, null);
    }

    private static WorkerCapabilities dockerCapabilities(Worker worker,
                                                        boolean executorEnabled,
                                                        Boolean dockerEnabled,
                                                        List<String> dockerAllowedImages,
                                                        Integer dockerMaxMemoryMb,
                                                        Integer dockerMaxCpuCores) {
        return capabilities(
                worker,
                executors(executor(DOCKER_EXECUTOR_ID, executorEnabled)),
                dockerEnabled,
                dockerAllowedImages == null ? null : textArray(dockerAllowedImages),
                dockerMaxMemoryMb,
                dockerMaxCpuCores
        );
    }

    private static WorkerCapabilities capabilities(Worker worker,
                                                   ArrayNode executors,
                                                   Boolean dockerEnabled,
                                                   ArrayNode dockerAllowedImages,
                                                   Integer dockerMaxMemoryMb,
                                                   Integer dockerMaxCpuCores) {
        WorkerCapabilities capabilities = WorkerCapabilities.create(worker);
        capabilities.replaceWith(
                BASE_TIME,
                executors,
                dockerEnabled,
                dockerAllowedImages,
                dockerMaxMemoryMb,
                dockerMaxCpuCores,
                dockerGpuAllowed(dockerEnabled, dockerAllowedImages, dockerMaxMemoryMb, dockerMaxCpuCores)
        );
        return capabilities;
    }

    private static Boolean dockerGpuAllowed(Boolean dockerEnabled,
                                            ArrayNode dockerAllowedImages,
                                            Integer dockerMaxMemoryMb,
                                            Integer dockerMaxCpuCores) {
        if (dockerEnabled == null
                && dockerAllowedImages == null
                && dockerMaxMemoryMb == null
                && dockerMaxCpuCores == null) {
            return null;
        }

        return false;
    }

    private static ObjectNode executor(String executorId, boolean enabled) {
        return JsonNodeFactory.instance.objectNode()
                .put("executorId", executorId)
                .put("executorContractVersion", EXECUTOR_CONTRACT_VERSION)
                .put("enabled", enabled);
    }

    private static ArrayNode executors(ObjectNode... executors) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (ObjectNode executor : executors) {
            array.add(executor);
        }
        return array;
    }

    private static ArrayNode textArray(List<String> values) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        values.forEach(array::add);
        return array;
    }
}
