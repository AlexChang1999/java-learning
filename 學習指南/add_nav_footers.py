# -*- coding: utf-8 -*-
"""
為所有 69 個學習章節加上學習順序導航頁腳。
執行方式：python add_nav_footers.py
"""

import os
import re

GUIDE_DIR = os.path.dirname(os.path.abspath(__file__))

# 10 個學習階段名稱
PHASE_NAMES = {
    1: "Java 語言核心",
    2: "電腦科學底層理論",
    3: "工程基礎",
    4: "資料庫 + Spring 後端",
    5: "進階後端技術",
    6: "容器化與 DevOps",
    7: "資料結構深化與效能調優",
    8: "領域實戰",
    9: "微服務與分散式架構",
    10: "架構設計與面試準備",
}

# 每個步驟對應的 Phase（步驟範圍 → 階段號）
def get_phase(step: int) -> int:
    if step <= 6:   return 1
    if step <= 14:  return 2
    if step <= 21:  return 3
    if step <= 29:  return 4
    if step <= 40:  return 5
    if step <= 43:  return 6
    if step <= 47:  return 7
    if step <= 54:  return 8
    if step <= 66:  return 9
    return 10

# 完整學習順序（69 步）：(順序, 章節號碼字串, 顯示名稱, 檔名)
SEQUENCE = [
    (1,  "01", "第一章：基礎語法與流程控制",             "01_第一章_基礎語法與流程控制.md"),
    (2,  "02", "第二章：物件導向程式設計",               "02_第二章_物件導向程式設計.md"),
    (3,  "07", "第七章：現代 Java 語法",                "07_第七章_現代Java語法.md"),
    (4,  "17", "第十七章：泛型深入",                    "17_第十七章_泛型深入.md"),
    (5,  "18", "第十八章：Annotation 與反射",           "18_第十八章_Annotation與反射.md"),
    (6,  "06", "第六章：遞迴與演算法複雜度",             "06_第六章_遞迴與演算法複雜度.md"),
    (7,  "05", "第五章：數字系統與浮點數陷阱",           "05_第五章_數字系統與浮點數陷阱.md"),
    (8,  "23", "第二十三章：計算機組成原理",             "23_第二十三章_計算機組成原理.md"),
    (9,  "24", "第二十四章：作業系統基礎",               "24_第二十四章_作業系統基礎.md"),
    (10, "04", "第四章：底層記憶體與 JVM 運作",          "04_第四章_底層記憶體與JVM運作.md"),
    (11, "11", "第十一章：JIT 編譯器與 JVM 深度",        "11_第十一章_JIT編譯器與JVM深度.md"),
    (12, "25", "第二十五章：GC 深度剖析",                "25_第二十五章_GC深度剖析.md"),
    (13, "09", "第九章：C++ 與 Java 的核心差異",         "09_第九章_C++與Java的核心差異.md"),
    (14, "12", "第十二章：硬體親和性與機械同理心",        "12_第十二章_硬體親和性與機械同理心.md"),
    (15, "15", "第十五章：設計模式",                    "15_第十五章_設計模式.md"),
    (16, "52", "第五十二章：Spring AOP 面向切面程式設計", "52_第五十二章_Spring_AOP面向切面程式設計.md"),
    (17, "16", "第十六章：測試驅動開發",                 "16_第十六章_測試驅動開發.md"),
    (18, "26", "第二十六章：Maven 與 Gradle",           "26_第二十六章_Maven與Gradle.md"),
    (19, "61", "第六十一章：Git 工作流程進階",           "61_第六十一章_Git工作流程進階.md"),
    (20, "03", "第三章：檔案 IO 與網路程式設計",          "03_第三章_檔案IO與網路程式設計.md"),
    (21, "13", "第十三章：網路協定底層與低延遲技術",       "13_第十三章_網路協定底層與低延遲技術.md"),
    (22, "19", "第十九章：資料庫與 JDBC",                "19_第十九章_資料庫與JDBC.md"),
    (23, "21", "第二十一章：Spring Boot 基礎",           "21_第二十一章_SpringBoot基礎.md"),
    (24, "27", "第二十七章：Spring Data JPA",           "27_第二十七章_SpringDataJPA.md"),
    (25, "54", "第五十四章：資料庫 Migration（Flyway）", "54_第五十四章_資料庫Migration與版本管理.md"),
    (26, "22", "第二十二章：REST API 設計",              "22_第二十二章_REST_API設計.md"),
    (27, "69", "第六十九章：GraphQL API 設計",           "69_第六十九章_GraphQL_API設計.md"),
    (28, "30", "第三十章：JWT 與 Spring Security",       "30_第三十章_JWT與SpringSecurity.md"),
    (29, "20", "第二十章：日誌框架",                    "20_第二十章_日誌框架.md"),
    (30, "10", "第十章：多執行緒與並發",                 "10_第十章_多執行緒與並發.md"),
    (31, "62", "第六十二章：NoSQL 資料庫選型總覽",        "62_第六十二章_NoSQL資料庫選型總覽.md"),
    (32, "29", "第二十九章：Redis 快取",                 "29_第二十九章_Redis快取.md"),
    (33, "48", "第四十八章：MongoDB 與 NoSQL 設計",      "48_第四十八章_MongoDB與NoSQL設計.md"),
    (34, "63", "第六十三章：Cassandra 深入實作",         "63_第六十三章_Cassandra深入實作.md"),
    (35, "28", "第二十八章：Kafka 訊息佇列",             "28_第二十八章_Kafka訊息佇列.md"),
    (36, "31", "第三十一章：WebSocket 即時通訊",         "31_第三十一章_WebSocket即時通訊.md"),
    (37, "53", "第五十三章：Elasticsearch 搜尋引擎",     "53_第五十三章_Elasticsearch搜尋引擎.md"),
    (38, "64", "第六十四章：ClickHouse 與 OLAP 分析資料庫", "64_第六十四章_ClickHouse與OLAP分析資料庫.md"),
    (39, "65", "第六十五章：時序數據庫",                 "65_第六十五章_時序數據庫.md"),
    (40, "66", "第六十六章：Neo4j 圖資料庫",             "66_第六十六章_Neo4j圖資料庫.md"),
    (41, "32", "第三十二章：Docker 容器化",              "32_第三十二章_Docker容器化.md"),
    (42, "34", "第三十四章：CI/CD 與 GitHub Actions",   "34_第三十四章_CICD與GitHub_Actions.md"),
    (43, "47", "第四十七章：Kubernetes 深入實戰",        "47_第四十七章_Kubernetes深入實戰.md"),
    (44, "33", "第三十三章：資料結構深入實作",            "33_第三十三章_資料結構深入實作.md"),
    (45, "44", "第四十四章：資料庫進階",                 "44_第四十四章_資料庫進階.md"),
    (46, "45", "第四十五章：效能分析與調優方法論",         "45_第四十五章_效能分析與調優方法論.md"),
    (47, "51", "第五十一章：整合測試與 TestContainers",  "51_第五十一章_整合測試與TestContainers.md"),
    (48, "08", "第八章：交易系統與撮合引擎基礎",          "08_第八章_交易系統與撮合引擎基礎.md"),
    (49, "14", "第十四章：撮合引擎完整實作",              "14_第十四章_撮合引擎完整實作.md"),
    (50, "35", "第三十五章：金融交易系統資料結構實戰",     "35_第三十五章_金融交易系統資料結構實戰.md"),
    (51, "36", "第三十六章：電商系統資料結構實戰",        "36_第三十六章_電商系統資料結構實戰.md"),
    (52, "37", "第三十七章：遊戲系統資料結構實戰",        "37_第三十七章_遊戲系統資料結構實戰.md"),
    (53, "38", "第三十八章：體育彩票系統資料結構實戰",    "38_第三十八章_體育彩票系統資料結構實戰.md"),
    (54, "39", "第三十九章：主流軟體底層資料結構解密",    "39_第三十九章_主流軟體底層資料結構解密.md"),
    (55, "40", "第四十章：微服務架構基礎",               "40_第四十章_微服務架構基礎.md"),
    (56, "59", "第五十九章：DDD 領域驅動設計落地",        "59_第五十九章_DDD領域驅動設計落地.md"),
    (57, "55", "第五十五章：gRPC 深入實作",              "55_第五十五章_gRPC深入實作.md"),
    (58, "41", "第四十一章：分散式系統設計原則",          "41_第四十一章_分散式系統設計原則.md"),
    (59, "58", "第五十八章：冪等性設計",                 "58_第五十八章_冪等性設計.md"),
    (60, "60", "第六十章：CQRS 與事件溯源",              "60_第六十章_CQRS與事件溯源.md"),
    (61, "42", "第四十二章：高可用與熔斷降級",            "42_第四十二章_高可用與熔斷降級.md"),
    (62, "43", "第四十三章：可觀測性工程",               "43_第四十三章_可觀測性工程.md"),
    (63, "67", "第六十七章：Spring Batch 批次處理",      "67_第六十七章_Spring_Batch批次處理.md"),
    (64, "68", "第六十八章：Netty 網路框架深入",         "68_第六十八章_Netty網路框架深入.md"),
    (65, "50", "第五十章：響應式程式設計（WebFlux）",     "50_第五十章_響應式程式設計WebFlux.md"),
    (66, "46", "第四十六章：安全工程實踐",               "46_第四十六章_安全工程實踐.md"),
    (67, "56", "第五十六章：系統設計方法論",             "56_第五十六章_系統設計方法論.md"),
    (68, "49", "第四十九章：系統設計面試實戰",            "49_第四十九章_系統設計面試實戰.md"),
    (69, "57", "第五十七章：進階系統設計實戰",            "57_第五十七章_進階系統設計實戰.md"),
]

# 標記頁腳的起始符號（用於識別並移除舊頁腳）
FOOTER_MARKER = "<!-- NAV_FOOTER_START -->"

def build_footer(step: int, total: int, next_step_info) -> str:
    """建立學習順序導航頁腳字串"""
    phase = get_phase(step)
    phase_name = PHASE_NAMES[phase]
    lines = [
        "",
        "---",
        "",
        FOOTER_MARKER,
        f"> 📖 **學習順序第 {step} 章**｜Phase {phase}：{phase_name}",
    ]
    if next_step_info:
        next_step, _, next_title, next_file = next_step_info
        lines.append(f"> ➡️ **下一章（第 {next_step} 章）**：[{next_title}]({next_file})")
    else:
        lines.append("> 🎓 **恭喜完成全部 69 個學習章節！你已具備 Java 架構師的完整知識體系。**")
    lines.append("")
    return "\n".join(lines)

def strip_old_footer(content: str) -> str:
    """移除文件末尾舊的導航頁腳（如果有的話）"""
    marker_idx = content.find(FOOTER_MARKER)
    if marker_idx == -1:
        return content
    # 找到 marker 前最後一個 --- 分隔線
    before_marker = content[:marker_idx]
    # 尋找緊接 marker 前的 --- 行
    sep_idx = before_marker.rfind("\n---\n")
    if sep_idx != -1:
        return content[:sep_idx].rstrip()
    return content[:marker_idx].rstrip()

def process_file(filepath: str, step: int, next_step_info) -> bool:
    """為單一檔案加上（或更新）導航頁腳，回傳是否成功"""
    try:
        with open(filepath, "r", encoding="utf-8") as f:
            content = f.read()
    except FileNotFoundError:
        print(f"  ⚠️  找不到檔案：{filepath}")
        return False

    # 移除舊頁腳
    content = strip_old_footer(content)
    content = content.rstrip()

    # 加上新頁腳
    footer = build_footer(step, 69, next_step_info)
    new_content = content + "\n" + footer

    with open(filepath, "w", encoding="utf-8") as f:
        f.write(new_content)
    return True

def main():
    print(f"📂 學習指南目錄：{GUIDE_DIR}")
    print(f"📝 處理 {len(SEQUENCE)} 個章節...\n")

    ok_count = 0
    fail_count = 0

    for i, (step, ch_num, title, filename) in enumerate(SEQUENCE):
        filepath = os.path.join(GUIDE_DIR, filename)
        next_info = SEQUENCE[i + 1] if i + 1 < len(SEQUENCE) else None

        success = process_file(filepath, step, next_info)
        status = "✅" if success else "❌"
        print(f"  {status}  Step {step:2d}  {filename}")
        if success:
            ok_count += 1
        else:
            fail_count += 1

    print(f"\n完成！成功：{ok_count}，失敗：{fail_count}")

if __name__ == "__main__":
    main()
