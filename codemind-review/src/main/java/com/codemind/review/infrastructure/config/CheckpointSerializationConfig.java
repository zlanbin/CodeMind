package com.codemind.review.infrastructure.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * checkpoint 专用的 ObjectMapper（M7a）。
 *
 * <p>节点结果以 {@code Object} 存取，必须把具体类型一起写进 JSON，否则读回来是
 * {@code LinkedHashMap}，{@code DagOrchestrator} 跨阶段转型就失败——
 * {@code DagScheduler.execute} 每次开头都 {@code loadCheckpoint}，而编排器调它三次，
 * 第三次会读回第二次写的 {@code ChunkReduceResult}（M3 技术债，M6 的验证点实际被它堵死）。
 *
 * <p><b>必须是独立实例</b>：容器里那个共享 {@code ObjectMapper} 还负责 RocketMQ 事件
 * 的序列化，给它开 default typing 会让事件 JSON 多出类型字段，破掉 integration ↔ review
 * 的跨服务契约。
 *
 * <p>类型白名单只放本项目包与 java.util：反序列化任意类名是已知的攻击面，
 * 即便这里的数据是自己写的也不开口子。
 */
@Configuration
public class CheckpointSerializationConfig {

    /** checkpoint 结果序列化用。名字固定，{@code JdbcNodeRunRepository} 按它注入。 */
    @Bean
    public ObjectMapper checkpointObjectMapper() {
        PolymorphicTypeValidator validator = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .allowIfSubType("com.codemind.")
                .allowIfSubType("java.util.")
                .build();
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        // 必须是 EVERYTHING 而非 NON_FINAL：节点结果（ChunkReduceResult / GlobalReduceResult /
        // ChangeMap / ReviewChunk）全是 record，record 是 final 类，NON_FINAL 按定义不给它们
        // 写类型 id，顶层读回来就还是 LinkedHashMap。
        mapper.activateDefaultTyping(validator, ObjectMapper.DefaultTyping.EVERYTHING,
                JsonTypeInfo.As.PROPERTY);
        return mapper;
    }
}
