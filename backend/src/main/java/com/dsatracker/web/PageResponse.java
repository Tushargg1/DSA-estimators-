package com.dsatracker.web;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * A stable, self-describing pagination envelope for list endpoints
 * (used by {@code GET /api/users/{id}/submissions}, task 8.7).
 *
 * <p>Spring Data's own {@code Page}/{@code PageImpl} JSON shape is not a stable
 * public contract (Spring even logs a warning when serializing it directly), so
 * this record exposes a small, explicit set of fields the frontend can rely on:
 * the page {@code content} plus the paging metadata it needs to render controls.
 *
 * @param content       the items on this page
 * @param page          zero-based page index
 * @param size          requested page size
 * @param totalElements total number of items across all pages
 * @param totalPages    total number of pages
 * @param first         whether this is the first page
 * @param last          whether this is the last page
 * @param <T>           element type
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    /**
     * Builds a {@link PageResponse} from a Spring Data {@link Page}, mapping each
     * entity element to its DTO with {@code mapper}.
     *
     * @param source the source page of entities
     * @param mapper entity-to-DTO mapping function
     * @param <E>    source (entity) type
     * @param <T>    target (DTO) type
     */
    public static <E, T> PageResponse<T> from(Page<E> source, Function<E, T> mapper) {
        return new PageResponse<>(
                source.getContent().stream().map(mapper).toList(),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages(),
                source.isFirst(),
                source.isLast());
    }
}
