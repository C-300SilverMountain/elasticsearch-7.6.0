/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.Function;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsException;
import org.elasticsearch.env.Environment;
import org.elasticsearch.node.Node;

public class InternalSettingsPreparer {

    private static final String SECRET_PROMPT_VALUE = "${prompt.secret}";
    private static final String TEXT_PROMPT_VALUE = "${prompt.text}";

    /**
     * Prepares settings for the transport client by gathering all
     * elasticsearch system properties and setting defaults.
     */
    public static Settings prepareSettings(Settings input) {
        Settings.Builder output = Settings.builder();
        initializeSettings(output, input, Collections.emptyMap());
        finalizeSettings(output, () -> null);
        return output.build();
    }

    /**
     * Prepares the settings by gathering all elasticsearch system properties, optionally loading the configuration settings.
     *
     * @param input      the custom settings to use; these are not overwritten by settings in the configuration file 【input=用户级别配置（次优先级），目前单位测试用到】
     * @param properties map of properties key/value pairs (usually from the command-line) 【此時的properties是命令行的參數】（最高优先级）
     * @param configPath path to config directory; (use null to indicate the default)
     * @param defaultNodeName supplier for the default node.name if the setting isn't defined
     * @return the {@link Environment}
     */
    public static Environment prepareEnvironment(Settings input, Map<String, String> properties,
            Path configPath, Supplier<String> defaultNodeName) {
        // just create enough settings to build the environment, to get the config dir
        //在 Java 中，Builder 设计模式通常用于构建复杂对象
        Settings.Builder output = Settings.builder();
        //【此時的properties是命令行的參數】
        initializeSettings(output, input, properties);
        Environment environment = new Environment(output.build(), configPath);

        if (Files.exists(environment.configFile().resolve("elasticsearch.yaml"))) {
            throw new SettingsException("elasticsearch.yaml was deprecated in 5.5.0 and must be renamed to elasticsearch.yml");
        }

        if (Files.exists(environment.configFile().resolve("elasticsearch.json"))) {
            throw new SettingsException("elasticsearch.json was deprecated in 5.5.0 and must be converted to elasticsearch.yml");
        }

        output = Settings.builder(); // start with a fresh output
        Path path = environment.configFile().resolve("elasticsearch.yml");
        //当elasticsearch.yml存在时，则加载该文件，并将该文件内容保存到output实例中
        if (Files.exists(path)) {
            try {
                output.loadFromPath(path);
            } catch (IOException e) {
                throw new SettingsException("Failed to load settings from " + path.toString(), e);
            }
        }
        //output(elasticsearch.yml) < input(用户自定义) < properties(命令行参数)
        //配置优先级：elasticsearch.yml (最低) < 用户自定义（方法参数） < 命令行参数 (最高)
        // re-initialize settings now that the config file has been loaded
        initializeSettings(output, input, properties);
        //检查废弃的 “命令行参数”
        checkSettingsForTerminalDeprecation(output);
        finalizeSettings(output, defaultNodeName);

        return new Environment(output.build(), configPath);
    }

    /**
     * Initializes the builder with the given input settings, and applies settings from the specified map (these settings typically come
     * from the command line).
     *
     * @param output the settings builder to apply the input and default settings to
     * @param input the input settings
     * @param esSettings a map from which to apply settings
     */
    static void initializeSettings(final Settings.Builder output, final Settings input, final Map<String, String> esSettings) {
        output.put(input);
        output.putProperties(esSettings, Function.identity());
        output.replacePropertyPlaceholders();
    }

    /**
     * Checks all settings values to make sure they do not have the old prompt settings. These were deprecated in 6.0.0.
     * This check should be removed in 8.0.0.
     */
    private static void checkSettingsForTerminalDeprecation(final Settings.Builder output) throws SettingsException {
        // This method to be removed in 8.0.0, as it was deprecated in 6.0 and removed in 7.0
        assert Version.CURRENT.major != 8: "Logic pertaining to config driven prompting should be removed";
        for (String setting : output.keys()) {
            switch (output.get(setting)) {
                case SECRET_PROMPT_VALUE:
                    throw new SettingsException("Config driven secret prompting was deprecated in 6.0.0. Use the keystore" +
                        " for secure settings.");
                case TEXT_PROMPT_VALUE:
                    throw new SettingsException("Config driven text prompting was deprecated in 6.0.0. Use the keystore" +
                        " for secure settings.");
            }
        }
    }

    /**
     * Finish preparing settings by replacing forced settings and any defaults that need to be added.
     */
    private static void finalizeSettings(Settings.Builder output, Supplier<String> defaultNodeName) {
        // allow to force set properties based on configuration of the settings provided
        List<String> forcedSettings = new ArrayList<>();
        for (String setting : output.keys()) {
            if (setting.startsWith("force.")) {
                forcedSettings.add(setting);
            }
        }
        for (String forcedSetting : forcedSettings) {
            String value = output.remove(forcedSetting);
            output.put(forcedSetting.substring("force.".length()), value);
        }
        output.replacePropertyPlaceholders();

        // put the cluster and node name if they aren't set
        if (output.get(ClusterName.CLUSTER_NAME_SETTING.getKey()) == null) {
            output.put(ClusterName.CLUSTER_NAME_SETTING.getKey(), ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY).value());
        }
        if (output.get(Node.NODE_NAME_SETTING.getKey()) == null) {
            output.put(Node.NODE_NAME_SETTING.getKey(), defaultNodeName.get());
        }
    }
}
