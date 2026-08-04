package dev.adrian.goral.localhivebackend.controller;

import dev.adrian.goral.localhivebackend.dto.AdminResearchProtocolContractResponseDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchProtocolValidationRequestDto;
import dev.adrian.goral.localhivebackend.dto.AdminResearchProtocolValidationResponseDto;
import dev.adrian.goral.localhivebackend.service.research.ResearchProtocolContractService;
import dev.adrian.goral.localhivebackend.service.research.ResearchProtocolContractValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/research")
@RequiredArgsConstructor
public class AdminResearchProtocolContractController {

    private final ResearchProtocolContractService contractService;
    private final ResearchProtocolContractValidator validator;

    @GetMapping("/protocol-contract")
    public ResponseEntity<AdminResearchProtocolContractResponseDto> getProtocolContract() {
        return ResponseEntity.ok(contractService.getContract());
    }

    @PostMapping("/protocol-contract/validate")
    public ResponseEntity<AdminResearchProtocolValidationResponseDto> validateProtocolContract(
            @RequestBody AdminResearchProtocolValidationRequestDto request
    ) {
        try {
            return ResponseEntity.ok(validator.validate(request));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
