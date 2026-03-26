//package com.auraboot.framework.application.sql.region;
//
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.jdbc.core.JdbcTemplate;
//import org.springframework.util.ResourceUtils;
//
//import java.io.File;
//import java.util.ArrayList;
//import java.util.List;
//
//@SpringBootTest
//public class AdministrativeDivisionImporter {
//
//    @Autowired
//    private JdbcTemplate jdbcTemplate;
//
//    private static final int BATCH_SIZE = 1000;
//    private long idCounter = 1;
//
//    /**
//     * 15s 本机导入完成
//     * @throws Exception
//     */
//    @Test
//    public void importData() throws Exception {
//        // 读取JSON文件
//        File file = ResourceUtils.getFile("classpath:pcas-code.json");
//        ObjectMapper mapper = new ObjectMapper();
//        JsonNode rootNode = mapper.readTree(file);
//
//        List<AdministrativeDivision> divisions = new ArrayList<>();
//
//        // 解析数据
//        for (JsonNode provinceNode : rootNode) {
//            parseNode(provinceNode, null, 1, divisions);
//        }
//
//        // 分批插入
//        insertBatch(divisions);
//    }
//
//    private void parseNode(JsonNode node, String parentCode, int level, List<AdministrativeDivision> divisions) {
//        String code = node.get("code").asText();
//        String name = node.get("name").asText();
//
//        AdministrativeDivision division = new AdministrativeDivision();
//        division.setId(idCounter++);
//        division.setCode(code);
//        division.setName(name);
//        division.setParentCode(parentCode);
//        division.setLevel(level);
//        division.setSortOrder(divisions.size());
//        division.setStatus("active");
//
//        divisions.add(division);
//
//        // 递归处理子节点
//        JsonNode children = node.get("children");
//        if (children != null && children.isArray()) {
//            for (JsonNode child : children) {
//                parseNode(child, code, level + 1, divisions);
//            }
//        }
//    }
//
//    private void insertBatch(List<AdministrativeDivision> divisions) {
//        String sql = "INSERT INTO ab_administrative_division (id, code, name, parent_code, level, sort_order, status) VALUES (?, ?, ?, ?, ?, ?, ?)";
//
//        for (int i = 0; i < divisions.size(); i += BATCH_SIZE) {
//            int end = Math.min(i + BATCH_SIZE, divisions.size());
//            List<AdministrativeDivision> batch = divisions.subList(i, end);
//
//            List<Object[]> batchArgs = new ArrayList<>();
//            for (AdministrativeDivision div : batch) {
//                batchArgs.add(new Object[]{
//                    div.getId(), div.getCode(), div.getName(),
//                    div.getParentCode(), div.getLevel(),
//                    div.getSortOrder(), div.getStatus()
//                });
//            }
//
//            jdbcTemplate.batchUpdate(sql, batchArgs);
//            System.out.println("已插入 " + end + "/" + divisions.size() + " 条记录");
//        }
//    }
//
//
//}