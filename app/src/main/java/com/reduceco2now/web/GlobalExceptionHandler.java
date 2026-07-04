package com.reduceco2now.web;

import com.reduceco2now.shared.error.DomainException;
import com.reduceco2now.shared.error.NotFoundException;
import com.reduceco2now.shared.web.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> notFound(NotFoundException e){
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiError> domain(DomainException e){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(e.code(), e.getMessage()));
    }
}
