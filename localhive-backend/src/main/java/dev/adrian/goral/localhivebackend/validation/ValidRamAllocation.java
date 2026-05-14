package dev.adrian.goral.localhivebackend.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Documented
@Constraint(validatedBy = RamAllocationValidator.class)
@Target(TYPE)
@Retention(RUNTIME)
public @interface ValidRamAllocation {

    String message() default "sharedRamMb cannot be greater than totalRamMb";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}