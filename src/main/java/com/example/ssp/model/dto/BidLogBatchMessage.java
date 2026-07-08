package com.example.ssp.model.dto;

import com.example.ssp.model.entity.BidLog;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BidLogBatchMessage {

    private String requestId;

    private List<BidLog> logs;
}
