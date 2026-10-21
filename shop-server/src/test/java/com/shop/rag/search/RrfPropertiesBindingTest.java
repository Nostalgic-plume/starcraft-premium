package com.shop.rag.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 application.yml 中的 rag.rrf 能真实绑定到 {@link RrfProperties}，
 * 并覆盖非法配置的回退行为。
 * <p>
 * 动机：RRF 权重是「配置驱动」的调优开关，若 yml 键名写错（如 weightKeyword 而非 weight-vector）
 * 会静默回落到默认值，评估对照会得到错误的「权重无效」结论，必须用测试兜住绑定链路。
 *
 * @author shop
 */
@DisplayName("RrfProperties - application.yml 配置绑定与非法值回退")
class RrfPropertiesBindingTest {

    private static RrfProperties bindFromYml() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application", new ClassPathResource("application.yml"));
        MutablePropertySources propertySources = new MutablePropertySources();
        sources.forEach(propertySources::addLast);

        RrfProperties properties = new RrfProperties();
        new Binder(ConfigurationPropertySources.from(propertySources))
                .bind("rag.rrf", Bindable.ofInstance(properties));
        return properties;
    }

    @Test
    @DisplayName("yml 中的 rag.rrf 正确绑定（键名与字段映射无误）")
    void ymlRrfShouldBind() throws Exception {
        RrfProperties properties = bindFromYml();

        assertEquals(60, properties.getK(), "应绑定 k=60");
        assertEquals(1.0, properties.getWeightVector(), 1e-9, "应绑定 weight-vector=1.0");
        // 2026-10-21：由 2.0 回调至 1.5（新评估集实测 MRR 0.959→0.973，见 evaluation-2026-10-21.md §3.4）
        assertEquals(1.5, properties.getWeightKeyword(), 1e-9, "应绑定 weight-keyword=1.5");
    }

    @Test
    @DisplayName("非法配置（k<=0 且权重为负）回退默认值，不抛异常")
    void illegalValuesShouldFallback() {
        RrfProperties properties = new RrfProperties();
        properties.setK(0);
        properties.setWeightVector(-1.0);

        properties.validate();

        assertEquals(60, properties.getK());
        assertEquals(1.0, properties.getWeightVector(), 1e-9);
        assertEquals(1.0, properties.getWeightKeyword(), 1e-9);
    }

    @Test
    @DisplayName("两路权重同时为 0 时回退，避免融合排序失去意义")
    void bothWeightsZeroShouldFallback() {
        RrfProperties properties = new RrfProperties();
        properties.setWeightVector(0.0);
        properties.setWeightKeyword(0.0);

        properties.validate();

        assertEquals(1.0, properties.getWeightVector(), 1e-9);
        assertEquals(1.0, properties.getWeightKeyword(), 1e-9);
    }

    @Test
    @DisplayName("单路权重为 0 属合法配置（等价于单路检索），不被回退")
    void singleZeroWeightIsLegal() {
        RrfProperties properties = new RrfProperties();
        properties.setWeightVector(0.0);

        properties.validate();

        assertEquals(0.0, properties.getWeightVector(), 1e-9, "单路权重置 0 是有意的单路对照配置");
        assertEquals(1.0, properties.getWeightKeyword(), 1e-9);
    }
}
