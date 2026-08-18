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

/**
 * Maps domain exceptions to RFC 9457 problem-detail responses.
 *
 * <p>Centralising this is what keeps {@link StockController} free of
 * {@code try}/{@code catch}: an exception thrown anywhere below the
 * controller travels up untouched and is converted here, once, into a
 * consistent JSON error body:
 *
 * <pre>{@code
 * {
 *   "type":   "about:blank",
 *   "title":  "Not Found",
 *   "status": 404,
 *   "detail": "No stock found for ticker 'ZZZZ'"
 * }
 * }</pre>
 *
 * <p>Handlers are selected by exception type, most specific first, so adding
 * a new failure mode means adding a class and a method here — not editing the
 * controller.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	/**
	 * Unknown ticker → {@code 404}.
	 *
	 * @param e the exception carrying the ticker that was not found
	 * @return a problem detail with status 404
	 */
	@ExceptionHandler(StockNotFoundException.class)
	public ProblemDetail stockNotFound(StockNotFoundException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	/**
	 * Cold cache plus a failing provider → {@code 503}.
	 *
	 * <p>{@code 503} rather than {@code 500}: the fault is upstream and
	 * transient, so the client should retry rather than treat it as a bug.
	 *
	 * @param e the exception explaining why no data could be served
	 * @return a problem detail with status 503
	 */
	@ExceptionHandler(DataUnavailableException.class)
	public ProblemDetail dataUnavailable(DataUnavailableException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
	}

	/**
	 * Unparseable {@code sortBy} or {@code order} → {@code 400}.
	 *
	 * <p>The message from {@code SortBy.from}/{@code Direction.from} is passed
	 * through verbatim because it already names the valid values, which is
	 * more useful to the caller than a generic "bad request".
	 *
	 * @param e the parse failure, whose message names the valid values
	 * @return a problem detail with status 400
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	public ProblemDetail invalidArgument(IllegalArgumentException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	/**
	 * Bean-validation failure on a parameter — {@code @Positive},
	 * {@code @PositiveOrZero}, {@code @Pattern} — → {@code 400}.
	 *
	 * <p>Two exception types are handled together because Spring reports the
	 * same class of failure differently depending on where the constraint sits.
	 *
	 * @param e the constraint violation, in either of its two forms
	 * @return a problem detail with status 400
	 */
	@ExceptionHandler({ ConstraintViolationException.class, HandlerMethodValidationException.class })
	public ProblemDetail invalidParameter(Exception e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"Invalid request parameter: " + e.getMessage());
	}

	/**
	 * Non-numeric value in a numeric parameter, e.g. {@code ?maxPe=cheap}
	 * → {@code 400}.
	 *
	 * <p>The detail is rewritten rather than forwarded: Spring's own message
	 * leaks internal type names into a public API response.
	 *
	 * @param e the mismatch, used only for the offending parameter's name
	 * @return a problem detail with status 400
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ProblemDetail typeMismatch(MethodArgumentTypeMismatchException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"Parameter '" + e.getName() + "' has an invalid value");
	}
}
