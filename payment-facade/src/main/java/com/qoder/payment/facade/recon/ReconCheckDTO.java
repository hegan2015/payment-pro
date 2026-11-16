package com.qoder.payment.facade.recon;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class ReconCheckDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String name;
    private Boolean pass;
    private String detail;
}