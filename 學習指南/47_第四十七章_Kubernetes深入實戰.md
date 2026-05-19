# 第四十七章：Kubernetes 深入實戰

## 前言：為什麼 Docker 不夠用？

Docker 讓你把應用打包成容器，但它只解決「單機運行」問題。生產環境的真實挑戰是：

- 容器掛掉要自動重啟
- 流量增大要自動擴容
- 發布新版本不能停機
- 幾十個容器要互相找到彼此
- 密碼/設定不能寫在映像檔裡

**Kubernetes（K8s）** 就是解決這些問題的容器編排平台。它把一群機器（Node）變成一個統一的運算池，讓你像指揮樂團一樣管理幾百個容器。

---

## 一、核心架構：K8s 由什麼組成？

```
┌─────────────────────────────────────────────────┐
│                  Kubernetes Cluster              │
│                                                  │
│  ┌──────────────────────────────────────────┐   │
│  │            Control Plane（大腦）           │   │
│  │  API Server  │  Scheduler  │  etcd        │   │
│  │  Controller Manager  │  Cloud Controller  │   │
│  └──────────────────────────────────────────┘   │
│                                                  │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────┐  │
│  │   Node 1    │  │   Node 2    │  │  Node 3 │  │
│  │  kubelet    │  │  kubelet    │  │ kubelet │  │
│  │  kube-proxy │  │  kube-proxy │  │k-proxy  │  │
│  │  [Pod][Pod] │  │  [Pod][Pod] │  │  [Pod]  │  │
│  └─────────────┘  └─────────────┘  └─────────┘  │
└─────────────────────────────────────────────────┘
```

| 元件 | 職責 |
|------|------|
| **API Server** | 所有操作的入口，`kubectl` 就是在呼叫它 |
| **etcd** | 分散式 K-V 資料庫，儲存整個叢集狀態 |
| **Scheduler** | 決定 Pod 要跑在哪個 Node |
| **Controller Manager** | 持續監控並使實際狀態符合期望狀態 |
| **kubelet** | 每個 Node 上的代理人，負責啟動/停止容器 |
| **kube-proxy** | 每個 Node 上的網路代理，實現 Service 的負載均衡 |

---

## 二、最小單位：Pod

Pod 是 K8s 調度的最小單位。一個 Pod 裡可以有多個容器，它們共享網路（同一個 IP）和儲存。

```yaml
# pod.yaml — 最簡單的 Pod
apiVersion: v1
kind: Pod
metadata:
  name: order-service-pod
  labels:
    app: order-service    # 標籤：用來被 Service 選取
spec:
  containers:
  - name: order-service
    image: myregistry/order-service:1.0.0
    ports:
    - containerPort: 8080
    env:
    - name: SPRING_PROFILES_ACTIVE
      value: "prod"
    resources:
      requests:           # 調度時保證這些資源
        memory: "256Mi"
        cpu: "250m"       # 250m = 0.25 核
      limits:             # 最多用這麼多
        memory: "512Mi"
        cpu: "500m"
    readinessProbe:       # 就緒探針：Pod 準備好接流量時才納入負載均衡
      httpGet:
        path: /actuator/health/readiness
        port: 8080
      initialDelaySeconds: 10
      periodSeconds: 5
    livenessProbe:        # 存活探針：容器卡死就重啟
      httpGet:
        path: /actuator/health/liveness
        port: 8080
      initialDelaySeconds: 30
      periodSeconds: 10
      failureThreshold: 3
```

> 💡 實際上你不會直接建 Pod，而是透過 Deployment 管理（Deployment 負責確保 Pod 數量和版本）。

---

## 三、Deployment：讓 Pod 有人管

Deployment 是最常用的工作負載資源，負責：
- 維持指定數量的 Pod 副本（Pod 死了自動重建）
- 滾動更新（新舊版本交替，不停機）
- 版本回滾

```yaml
# deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: order-service
  namespace: production     # 命名空間，用來隔離不同環境
spec:
  replicas: 3               # 維持 3 個 Pod 副本
  selector:
    matchLabels:
      app: order-service    # 管理帶有這個標籤的 Pod
  strategy:
    type: RollingUpdate     # 滾動更新策略
    rollingUpdate:
      maxSurge: 1           # 更新期間最多多出幾個 Pod
      maxUnavailable: 0     # 更新期間最多有幾個 Pod 不可用（0 = 始終保持服務）
  template:                 # Pod 的模板（以下等同上面的 pod.yaml spec）
    metadata:
      labels:
        app: order-service
    spec:
      containers:
      - name: order-service
        image: myregistry/order-service:1.2.0   # ← 修改這裡觸發滾動更新
        ports:
        - containerPort: 8080
        resources:
          requests:
            memory: "256Mi"
            cpu: "250m"
          limits:
            memory: "512Mi"
            cpu: "500m"
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 15
          periodSeconds: 5
```

```bash
# 常用操作
kubectl apply -f deployment.yaml       # 建立或更新
kubectl get deployments -n production  # 查看狀態
kubectl rollout status deployment/order-service -n production  # 觀察更新進度
kubectl rollout undo deployment/order-service -n production    # 回滾到上個版本
kubectl rollout history deployment/order-service               # 查看版本歷史
```

---

## 四、Service：讓 Pod 可以被找到

Pod 每次重啟 IP 都會變，Service 提供一個穩定的虛擬 IP 和 DNS 名稱。

```yaml
# service.yaml
apiVersion: v1
kind: Service
metadata:
  name: order-service
  namespace: production
spec:
  selector:
    app: order-service    # 代理所有帶這個標籤的 Pod
  ports:
  - port: 80              # Service 暴露的 port
    targetPort: 8080      # Pod 的 port
  type: ClusterIP         # 只在叢集內部可訪問（預設）
```

**Service 三種類型：**

| 類型 | 用途 |
|------|------|
| `ClusterIP` | 叢集內部服務間通信（最常用） |
| `NodePort` | 暴露到每個 Node 的固定 Port，適合測試 |
| `LoadBalancer` | 建立雲端負載均衡器（AWS ALB / GCP LB），用於生產外部流量 |

---

## 五、Ingress：對外的 HTTP 路由規則

LoadBalancer 每個 Service 都需要一個外部 IP（費用高）。Ingress 讓你用一個 IP 路由所有 HTTP/HTTPS 流量：

```yaml
# ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: api-ingress
  namespace: production
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /
    cert-manager.io/cluster-issuer: "letsencrypt-prod"  # 自動申請 HTTPS 憑證
spec:
  ingressClassName: nginx
  tls:
  - hosts:
    - api.myapp.com
    secretName: api-tls-cert
  rules:
  - host: api.myapp.com
    http:
      paths:
      - path: /orders
        pathType: Prefix
        backend:
          service:
            name: order-service    # → ClusterIP Service
            port:
              number: 80
      - path: /payments
        pathType: Prefix
        backend:
          service:
            name: payment-service
            port:
              number: 80
```

```
外部流量 → Ingress Controller（nginx pod）→ Ingress 規則 → ClusterIP Service → Pod
```

---

## 六、ConfigMap & Secret：設定與密碼管理

**不要把設定和密碼寫在映像檔裡。** 映像檔是公開的，而且每次改設定就要重新 build。

```yaml
# configmap.yaml — 非機密設定
apiVersion: v1
kind: ConfigMap
metadata:
  name: order-service-config
data:
  application.yml: |
    server:
      port: 8080
    spring:
      datasource:
        url: jdbc:mysql://mysql-service:3306/orders
```

```yaml
# secret.yaml — 機密資訊（base64 編碼，但不是加密！）
apiVersion: v1
kind: Secret
metadata:
  name: order-service-secret
type: Opaque
data:
  db-password: cGFzc3dvcmQxMjM=   # echo -n "password123" | base64
  jwt-secret: bXlfc2VjcmV0X2tleQ==
```

```yaml
# 在 Deployment 中引用
spec:
  containers:
  - name: order-service
    image: myregistry/order-service:1.2.0
    volumeMounts:
    - name: config-volume
      mountPath: /config           # ConfigMap 掛載為檔案
    env:
    - name: DB_PASSWORD            # Secret 注入為環境變數
      valueFrom:
        secretKeyRef:
          name: order-service-secret
          key: db-password
  volumes:
  - name: config-volume
    configMap:
      name: order-service-config
```

> 🔴 生產環境不要把 Secret YAML 提交到 Git！使用 Sealed Secrets 或 Vault 加密後再存。

---

## 七、HPA：自動水平擴縮容 <!-- 💡 進階 -->

HPA（Horizontal Pod Autoscaler）根據 CPU 使用率（或自定義指標）自動調整 Pod 數量：

```yaml
# hpa.yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: order-service-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: order-service
  minReplicas: 2        # 最少 2 個（高可用，不能只有 1 個）
  maxReplicas: 10       # 最多 10 個
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 60   # CPU 超過 60% 就擴容
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 70
```

```bash
kubectl get hpa -n production        # 查看 HPA 狀態
# NAME                  REFERENCE          TARGETS   MINPODS  MAXPODS  REPLICAS
# order-service-hpa     Deployment/...     45%/60%   2        10       3
```

**擴容觸發流程：**
```
流量增加 → CPU 上升超過 60% → HPA 偵測到（每 15 秒評估一次）
→ 計算需要 Pod 數 = ceil(current * currentMetric / desiredMetric)
→ 更新 Deployment replicas → Scheduler 選 Node → 啟動新 Pod
```

---

## 八、Namespace：多環境隔離 <!-- 💡 進階 -->

```bash
# 建立命名空間
kubectl create namespace staging
kubectl create namespace production

# 在特定 namespace 操作
kubectl apply -f deployment.yaml -n staging
kubectl get pods -n production
kubectl get pods --all-namespaces     # 看全部

# 設定預設 namespace（省略 -n）
kubectl config set-context --current --namespace=production
```

**常見 Namespace 設計：**
```
default       - 不建議使用（沒有隔離）
kube-system   - K8s 系統元件（不要動）
staging       - 測試環境
production    - 生產環境
monitoring    - Prometheus + Grafana
logging       - ELK / Loki
```

**ResourceQuota：限制每個 namespace 的資源用量**

```yaml
apiVersion: v1
kind: ResourceQuota
metadata:
  name: production-quota
  namespace: production
spec:
  hard:
    requests.cpu: "10"       # 整個 namespace 最多申請 10 核
    requests.memory: 20Gi
    limits.cpu: "20"
    limits.memory: 40Gi
    pods: "50"               # 最多 50 個 Pod
```

---

## 九、RBAC：權限控制 <!-- 🔴 資深 -->

K8s 的 RBAC（Role-Based Access Control）控制誰可以對哪些資源做什麼操作：

```yaml
# role.yaml — 定義權限（namespace 範圍）
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: developer-role
  namespace: staging
rules:
- apiGroups: ["apps"]
  resources: ["deployments", "pods"]
  verbs: ["get", "list", "watch", "create", "update"]
- apiGroups: [""]
  resources: ["pods/log"]
  verbs: ["get", "list"]
# 開發者可以查看/更新 deployment 和看 log，但不能刪除

---
# rolebinding.yaml — 把角色綁定給使用者
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: developer-binding
  namespace: staging
subjects:
- kind: User
  name: alice@company.com
  apiGroup: rbac.authorization.k8s.io
roleRef:
  kind: Role
  name: developer-role
  apiGroup: rbac.authorization.k8s.io
```

---

## 十、實際部署 Spring Boot 到 K8s <!-- 🔴 資深 -->

完整的生產部署流程：

```yaml
# k8s/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: order-service
  namespace: production
spec:
  replicas: 3
  selector:
    matchLabels:
      app: order-service
      version: "1.2.0"
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  template:
    metadata:
      labels:
        app: order-service
        version: "1.2.0"
    spec:
      # 優雅關閉：收到 SIGTERM 後等 30 秒讓 Pod 處理完進行中的請求
      terminationGracePeriodSeconds: 30
      # 反親和性：把 Pod 盡量分散到不同 Node（避免單點故障）
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
          - weight: 100
            podAffinityTerm:
              labelSelector:
                matchExpressions:
                - key: app
                  operator: In
                  values: ["order-service"]
              topologyKey: kubernetes.io/hostname
      containers:
      - name: order-service
        image: myregistry/order-service:1.2.0
        ports:
        - containerPort: 8080
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "prod"
        - name: JAVA_OPTS
          value: "-Xms256m -Xmx512m -XX:+UseG1GC"
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 20
          periodSeconds: 5
          failureThreshold: 3
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 15
          failureThreshold: 3
        lifecycle:
          preStop:
            exec:
              # SIGTERM 前先等 5 秒，讓 Service 把流量切走
              command: ["/bin/sh", "-c", "sleep 5"]
```

**GitHub Actions 自動部署：**

```yaml
# .github/workflows/deploy.yml
- name: Deploy to K8s
  run: |
    # 更新映像檔版本（觸發滾動更新）
    kubectl set image deployment/order-service \
      order-service=myregistry/order-service:${{ github.sha }} \
      -n production
    
    # 等待更新完成
    kubectl rollout status deployment/order-service -n production --timeout=5m
```

---

## 十一、除錯技巧

```bash
# Pod 狀態異常排查
kubectl get pods -n production
kubectl describe pod <pod-name> -n production   # 看 Events，找啟動失敗原因
kubectl logs <pod-name> -n production           # 看應用日誌
kubectl logs <pod-name> -n production --previous  # 看上次崩潰的日誌

# 進入容器內部除錯
kubectl exec -it <pod-name> -n production -- /bin/sh

# 查看資源用量
kubectl top pods -n production
kubectl top nodes

# 臨時暴露服務到本機除錯
kubectl port-forward service/order-service 8080:80 -n production
```

---

## 本章練習題

**Q1：Pod 和 Deployment 的差別是什麼？為什麼不直接建 Pod？**
<details>
<summary>答案</summary>
Pod 是最小運行單位，但它沒有「自癒」能力——Pod 掛掉就沒了，沒有人會重建它。Deployment 是 Pod 的管理者，它持續監控 Pod 數量是否符合 replicas 設定，不足就補，多了就刪。同時 Deployment 負責滾動更新和版本回滾。實際上你幾乎永遠應該透過 Deployment 管理 Pod，而不是直接建立裸 Pod。
</details>

**Q2：readinessProbe 和 livenessProbe 的差別是什麼？**
<details>
<summary>答案</summary>
readinessProbe（就緒探針）：判斷 Pod 是否準備好接收流量。探針失敗時，Pod 會從 Service 的負載均衡中移除，但 Pod 本身不會重啟。適合用在應用啟動中、暫時過載、連不到依賴服務等情況。

livenessProbe（存活探針）：判斷 Pod 是否還活著（沒有死鎖/卡死）。探針失敗時，kubelet 會重啟容器。設定要比 readiness 寬鬆，避免誤重啟。

兩個通常都要設，readiness 讓應用啟動期間不接流量，liveness 讓卡死的容器自動恢復。
</details>

**Q3：HPA 擴容後，流量降低了，Pod 數量會立刻縮回去嗎？**
<details>
<summary>答案</summary>
不會立刻。K8s HPA 有縮容冷卻時間（預設 5 分鐘），避免頻繁擴縮（稱為「thrashing」）。縮容會等 CPU 低於閾值持續 5 分鐘後才觸發。可以透過 `--horizontal-pod-autoscaler-downscale-stabilization` 調整。擴容則較快（通常 15-30 秒評估週期）。
</details>

---

<!-- NAV_FOOTER_START -->
> 學習順序第 43 章 | Phase 6：容器化與 DevOps
> 下一章（第 44 章）：[第三十三章：資料結構深入實作](33_第三十三章_資料結構深入實作.md)
