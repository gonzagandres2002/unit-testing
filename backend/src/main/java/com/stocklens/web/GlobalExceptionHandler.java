package com.stocklens.web;

import com.stocklens.service.DataUnavailableException;
import com.stocklens.service.StockNotFoundException;

import jakarta.validation.ConstraintViolationException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps domain exceptions to RFC 9457 problem-detail responses. */
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(StockNotFoundException.class)
	public ProblemDetail stockNotFound(StockNotFoundException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	@ExceptionHandler(DataUnavailableException.class)
	public ProblemDetail dataUnavailable(DataUnavailableException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ProblemDetail invalidArgument(IllegalArgumentException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler({ ConstraintViolationException.class, HandlerMethodValidationException.class })
	public ProblemDetail invalidParameter(Exception e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"Invalid request parameter: " + e.getMessage());
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ProblemDetail typeMismatch(MethodArgumentTypeMismatchException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"Parameter '" + e.getName() + "' has an invalid value");
	}
}
