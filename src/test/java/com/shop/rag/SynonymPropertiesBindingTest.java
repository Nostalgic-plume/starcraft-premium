package com.shop.rag;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 application.yml 中的中文同义词组能真实绑定到 {@link SynonymProperties}
 * 并驱动 {@link SynonymExpander} 生效。
 * <p>
 * 动机：同义词表是「配置驱动」的，若 yml 语法或中文编码有误，功能会静默失效
 * （只留一条启动 warn），必须用测试兜住「配置 → 绑定 → 扩展」这条链路。
 */
@DisplayName("SynonymProperties - application.yml 配置绑定")
class SynonymPropertiesBindingTest {

    @Test
    @DisplayName("yml 中文同义词组正确绑定并驱动扩展")
    void ymlGroupsShouldBindAndDriveExpansion() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application", new ClassPathResource("application.yml"));
        MutablePropertySources propertySources = new MutablePropertySources();
        sources.forEach(propertySources::addLast);

        SynonymProperties properties = new SynonymProperties();
        new Binder(ConfigurationPropertySources.from(propertySources))
                .bind("rag.synonym", Bindable.ofInstance(properties));

        assertTrue(properties.isEnabled(), "默认应启用同义词扩展");
        assertEquals(5, properties.getGroups().size(), "应绑定到 5 组同义词");

        SynonymExpander expander = new SynonymExpander(properties);
        expander.buildIndex();

        assertEquals("充电宝 移动电源 充电电池", expander.expand("充电宝"));
        assertEquals("刮胡刀 剃须刀 电动剃须刀", expander.expand("刮胡刀"));
    }
}
