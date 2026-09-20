# Elasticsearch 8.11.4 + IK 中文分词器
# 用途：为关键词检索路提供 BM25 打分与真中文分词（MySQL FULLTEXT 无 BM25，且 ngram 仅做 2-gram 粗切）
# 构建：docker compose build elasticsearch（首次 `docker compose up -d` 会自动构建）
# 说明：IK 版本号必须与 ES 版本严格一致，升级 ES 时同步修改下方两个 ARG；版本号是本文件的唯一事实来源
ARG ES_VERSION=8.11.4
FROM elasticsearch:${ES_VERSION}

ARG ES_VERSION
ARG IK_VERSION=${ES_VERSION}

# 官方发布站按版本直出 zip；GitHub Releases 已不再逐版本发布（v8.11.4 标签 404，勿改回 GitHub 下载）
# --batch 免交互，插件解压至 /usr/share/elasticsearch/plugins/analysis-ik
RUN bin/elasticsearch-plugin install --batch \
      "https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-${IK_VERSION}.zip"

# 构建期自检：插件未落盘即让镜像构建失败，避免带着“无分词器”的镜像上线
RUN test -d /usr/share/elasticsearch/plugins/analysis-ik
