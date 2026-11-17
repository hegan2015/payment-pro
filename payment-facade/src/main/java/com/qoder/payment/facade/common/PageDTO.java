package com.qoder.payment.facade.common;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 通用分页结果。 */
@Data
public class PageDTO<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private List<T> items;
    private int page;
    private int size;
    private long total;

    public static <T> PageDTO<T> of(List<T> items, int page, int size, long total) {
        PageDTO<T> dto = new PageDTO<>();
        dto.setItems(items);
        dto.setPage(page);
        dto.setSize(size);
        dto.setTotal(total);
        return dto;
    }
}