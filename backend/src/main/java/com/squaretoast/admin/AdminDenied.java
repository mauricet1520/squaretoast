package com.squaretoast.admin;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A refused admin call. Answered with an empty body so a refusal never echoes ids back: foreign or unknown ids are
 * 404, a missing permission on your own merchant's data is 403, a broken rule is 409, bad input 400.
 */
final class AdminDenied extends RuntimeException {

    private static final long serialVersionUID = 1L;

    final HttpStatus status;

    private AdminDenied(HttpStatus status) {
        super(status.getReasonPhrase(), null, false, false);
        this.status = status;
    }

    static AdminDenied notFound() {
        return new AdminDenied(HttpStatus.NOT_FOUND);
    }

    static AdminDenied forbidden() {
        return new AdminDenied(HttpStatus.FORBIDDEN);
    }

    static AdminDenied conflict() {
        return new AdminDenied(HttpStatus.CONFLICT);
    }

    static AdminDenied badRequest() {
        return new AdminDenied(HttpStatus.BAD_REQUEST);
    }

    @RestControllerAdvice(basePackageClasses = AdminDenied.class)
    static final class Handler {
        @ExceptionHandler(AdminDenied.class)
        ResponseEntity<Void> handle(AdminDenied e) {
            return ResponseEntity.status(e.status).build();
        }
    }
}
