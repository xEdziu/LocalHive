package dev.adrian.goral.localhivebackend.validation;

import dev.adrian.goral.localhivebackend.dto.WorkerRegistrationRequestDto;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class RamAllocationValidator implements ConstraintValidator<ValidRamAllocation, WorkerRegistrationRequestDto> {

    @Override
    public boolean isValid(WorkerRegistrationRequestDto dto, ConstraintValidatorContext context) {
        if (dto == null) {
            return true;
        }

        if (dto.getTotalRamMb() < 0 || dto.getSharedRamMb() < 0) {
            return true;
        }

        if (dto.getSharedRamMb() <= dto.getTotalRamMb()) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate("sharedRamMb cannot be greater than totalRamMb")
                .addPropertyNode("sharedRamMb")
                .addConstraintViolation();

        return false;
    }
}