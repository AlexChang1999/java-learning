# 第三章：檔案 I/O 與網路程式設計
> 對應原始碼：`Brad44` ~ `Brad63`

---

## I/O 的概念

I/O 代表 Input（輸入）和 Output（輸出）。在 Java 裡，讀寫檔案、網路傳輸都是 I/O 操作。

**Java I/O 兩大類型：**
- **位元組流（Byte Stream）**：用 `InputStream` / `OutputStream`，適合圖片、音訊等二進位檔案
- **字元流（Character Stream）**：用 `Reader` / `Writer`，適合文字檔案

---

## 第 44 ~ 46 課 — 認識 File 物件

### Brad44：路徑分隔符號（跨平台）

```java
public class Brad44 {
    public static void main(String[] args) {
        // 路徑分隔符號（不同系統不同）
        System.out.println(File.pathSeparator);  
        // Windows: ";"   Linux/Mac: ":"
        
        System.out.println(File.separator);
        // Windows: "\"   Linux/Mac: "/"
        
        // 好習慣：使用 File.separator 而非直接寫 "/" 或 "\"
        // 這樣程式在不同作業系統都能正常執行
    }
}
```

### Brad45：File 物件基本操作

```java
public class Brad45 {
    public static void main(String[] args) {
        File f1 = new File("d:/brad");    // 建立 File 物件（只是記錄路徑，不實際操作）
        System.out.println(f1.exists());  // 檢查路徑是否存在
        
        File root = new File(".");        // "." 代表目前工作目錄
        System.out.println(root.exists());          // true
        System.out.println(root.getAbsolutePath()); // 印出完整路徑
        
        File dir1 = new File("dir1");     // 相對路徑（相對於工作目錄）
        System.out.println(dir1.exists()); // 若 dir1 資料夾存在才是 true
    }
}
```

### Brad46：建立與移動檔案

```java
public class Brad46 {
    public static void main(String[] args) {
        File f1 = new File("./dir1/file1.txt");
        
        if (!f1.exists()) {
            try {
                f1.createNewFile();  // 建立新檔案
                System.out.println("OK");
            } catch (IOException e) {
                System.out.println(e);
            }
        } else {
            File f2 = new File("./dir2/file1.txt");
            f1.renameTo(f2);  // 移動並重新命名（類似剪下+貼上）
        }
    }
}
```

---

## 第 47 ~ 52 課 — 位元組流（Byte Stream）

### Brad47：FileOutputStream 寫入文字

```java
public class Brad47 {
    public static void main(String[] args) {
        String s1 = "\nHello, Brad";
        
        // try-with-resources：自動關閉資源（Java 7+）
        // 括號裡建立的物件，離開 try 區塊後自動呼叫 close()
        try (FileOutputStream fout = new FileOutputStream("dir1/file2.txt", true)) {
            // true = append 模式（附加到檔案末尾），false = 覆蓋
            fout.write(s1.getBytes());  // 字串轉 byte 陣列後寫入
            System.out.println("OK");
        } catch (Exception e) {
            System.out.println(e);
        }
    }
}
```

### Brad48：FileInputStream 讀取（緩衝方式）

```java
public class Brad48 {
    public static void main(String[] args) {
        try (FileInputStream fin = new FileInputStream("dir1/file3.txt")) {
            int len;
            byte[] b = new byte[3];  // 每次讀 3 個位元組
            
            // read(b) 回傳實際讀到的位元組數，-1 表示到達檔案末尾
            while ((len = fin.read(b)) != -1) {
                System.out.print(new String(b, 0, len));  // 只印出有效的部分
            }
        } catch (Exception e) {
            System.out.println(e);
        }
    }
}
```

### Brad49：一次讀完整個檔案

```java
public class Brad49 {
    public static void main(String[] args) {
        File file = new File("dir1/file3.txt");
        try (FileInputStream fin = new FileInputStream(file)) {
            long len = file.length();          // 取得檔案大小（位元組數）
            byte[] buf = new byte[(int)len];   // 一次性分配足夠空間
            fin.read(buf);                      // 一次讀完
            System.out.println(new String(buf)); // 直接轉成字串輸出
        } catch (Exception e) {
            System.out.println(e);
        }
    }
}
```

### Brad51/52：複製圖片（兩種方式比較）

```java
// Brad51：逐位元組複製（速度極慢！）
// 每次 read() 和 write() 都是一次系統呼叫，非常耗時
try (FileInputStream fin = new FileInputStream("dir1/coffee.jpg");
     FileOutputStream fout = new FileOutputStream("dir2/coffee.jpg")) {
    int b;
    while ((b = fin.read()) != -1) {  // 每次只讀 1 byte
        fout.write(b);
    }
}

// Brad52：緩衝區複製（速度快很多！）
// 一次讀取 4KB，大幅減少系統呼叫次數
try (FileInputStream fin = new FileInputStream("dir1/coffee.jpg");
     FileOutputStream fout = new FileOutputStream("dir2/coffee1.jpg")) {
    int len;
    byte[] buf = new byte[4 * 1024];  // 4KB 緩衝區
    while ((len = fin.read(buf)) != -1) {
        fout.write(buf, 0, len);  // 只寫入有效的部分
    }
}
```

---

## 第 50、53 ~ 54 課 — 字元流與 BufferedReader

### Brad50：FileReader（字元流）

```java
public class Brad50 {
    public static void main(String[] args) {
        // FileReader 用字元（char）而非位元組讀取，適合文字檔
        try (FileReader reader = new FileReader("dir1/file3.txt")) {
            int c;
            while ((c = reader.read()) != -1) {  // 每次讀一個字元
                System.out.print((char)c);         // 轉型成 char 後輸出
            }
        } catch (Exception e) {
            System.out.println(e);
        }
    }
}
```

### Brad53：BufferedReader 讀取 CSV（逐行讀取）

```java
public class Brad53 {
    public static void main(String[] args) {
        // 三層包裝：FileInputStream → InputStreamReader → BufferedReader
        // 這是 Decorator 設計模式的應用（層層包裝增加功能）
        try (FileInputStream fin = new FileInputStream("dir1/ns1hosp.csv");
             InputStreamReader inr = new InputStreamReader(fin);  // 位元組流→字元流
             BufferedReader br = new BufferedReader(inr)) {       // 加入緩衝功能
            
            br.readLine();  // 跳過第一行（CSV 標頭）
            String line;
            while ((line = br.readLine()) != null) {  // 逐行讀取
                try {
                    String[] data = line.split(",");  // 用逗號分割
                    // 取得第3、5、8欄（索引2、4、7）
                    System.out.printf("%s:%s:%s\n", data[2], data[4], data[7]);
                } catch (Exception e) {}  // 跳過格式不符的行
            }
        } catch (Exception e) {
            System.out.println(e);
        }
    }
}
```

**流的包裝層次（Decorator 模式）：**
```
FileInputStream（最底層，讀取位元組）
  └─ InputStreamReader（位元組 → 字元）
       └─ BufferedReader（加入緩衝 + readLine() 功能）
```

---

## 第 55 ~ 58 課 — 物件序列化（Serialization）

### Brad55：儲存物件到檔案

```java
public class Brad55 {
    public static void main(String[] args) {
        Bike b1 = new Bike();
        b1.upSpeed().upSpeed().upSpeed().upSpeed();
        
        // ObjectOutputStream：把物件「序列化」成位元組存入檔案
        try (ObjectOutputStream oout = new ObjectOutputStream(
                new FileOutputStream("dir1/b1.bike"))) {
            oout.writeObject(b1);  // 寫入物件
            System.out.println("OK");
        } catch (Exception e) {
            System.out.println(e);
        }
    }
}
// ⚠️ Bike 類別必須 implements Serializable 才能被序列化！
```

### Brad56：從檔案讀取物件

```java
public class Brad56 {
    public static void main(String[] args) {
        // ObjectInputStream：從檔案「反序列化」還原物件
        try (ObjectInputStream oin = new ObjectInputStream(
                new FileInputStream("dir1/b1.bike"))) {
            Object obj = oin.readObject();
            Bike b1 = (Bike)obj;  // 強制轉型回 Bike
            System.out.println(b1); // 印出 Speed: 3.841600（原來的速度恢復了！）
        } catch (Exception e) {
            System.out.println(e);
        }
    }
}
```

### Brad57：序列化多個物件

```java
// Student 類別要 implements Serializable
class Student implements Serializable {
    private String name;
    private int ch, eng, math;
    private Bike bike;  // Bike 也要是 Serializable
    
    int sum() { return ch + eng + math; }
    double avg() { return sum() / 3.0; }
}

// 寫入多個物件
try (ObjectOutputStream oout = new ObjectOutputStream(...)) {
    oout.writeObject(s1);
    oout.writeObject(s2);
}

// 讀取時按相同順序讀
try (ObjectInputStream oin = new ObjectInputStream(...)) {
    Student ss1 = (Student)oin.readObject();  // 第一個
    Student ss2 = (Student)oin.readObject();  // 第二個
}
```

---

## 第 59 ~ 63 課 — 網路程式設計

### Brad59：InetAddress（IP 位址）

```java
public class Brad59 {
    public static void main(String[] args) {
        try {
            // 根據 IP 字串建立 InetAddress 物件
            InetAddress ip = InetAddress.getByName("192.168.3.4");
            System.out.println(ip.getHostAddress());  // 印出 IP 字串
            // 也可以傳入域名：InetAddress.getByName("google.com")
        } catch (UnknownHostException e) {
            System.out.println(e);
        }
    }
}
```

### Brad60/61：UDP 傳輸（無連線）

**UDP 特性：** 速度快，但不保證送達、不保證順序

```java
// Brad60：UDP 傳送端（Client）
public class Brad60 {
    public static void main(String[] args) {
        String mesg = "Hello, BradV2";
        byte[] data = mesg.getBytes();
        
        try (DatagramSocket socket = new DatagramSocket()) {
            // DatagramPacket：包含「資料」、「目標 IP」、「目標 Port」
            DatagramPacket packet = new DatagramPacket(
                data, data.length,
                InetAddress.getByName("10.0.102.255"),  // 目標 IP（廣播）
                8888                                      // 目標 Port
            );
            socket.send(packet);  // 送出去就不管了
            System.out.println("UDP Send OK");
        } catch (Exception e) {
            System.out.println(e);
        }
    }
}

// Brad61：UDP 接收端（Server）
public class Brad61 {
    public static void main(String[] args) {
        while (true) {
            byte[] buf = new byte[1024];
            try (DatagramSocket socket = new DatagramSocket(8888)) {  // 監聽 8888 port
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);  // 阻塞等待，直到收到資料
                
                String senderIp = packet.getAddress().getHostAddress();
                String mesg = new String(packet.getData(), 0, packet.getLength());
                System.out.printf("%s : %s\n", senderIp, mesg);
                
                if (mesg.equals("bye")) break;  // 收到 bye 才結束
            } catch (Exception e) {
                System.out.println(e);
            }
        }
    }
}
```

### Brad62/63：TCP 傳輸（有連線）

**TCP 特性：** 需要建立連線，可靠傳輸，保證送達和順序

```java
// Brad62：TCP 客戶端（Client）— 主動連線
public class Brad62 {
    public static void main(String[] args) {
        String mesg = "Hello, TCP";
        
        // Socket：代表一個 TCP 連線端點
        try (Socket socket = new Socket(
                 InetAddress.getByName("10.0.102.74"),  // 伺服器 IP
                 9999);                                    // 伺服器 Port
             BufferedOutputStream out = new BufferedOutputStream(
                 socket.getOutputStream())) {             // 取得輸出流
            
            out.write(mesg.getBytes());  // 送出資料
        } catch (Exception e) {
            System.out.println(e);
        }
    }
}

// Brad63：TCP 伺服器端（Server）— 被動等待
public class Brad63 {
    public static void main(String[] args) {
        // ServerSocket：在指定 port 等待連線
        try (ServerSocket server = new ServerSocket(9999);
             Socket socket = server.accept();  // 阻塞等待，直到有客戶端連入
             BufferedReader reader = new BufferedReader(
                 new InputStreamReader(socket.getInputStream()))) {  // 取得輸入流
            
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.print(line);  // 印出收到的每一行
            }
        } catch (Exception e) {
            System.out.println(e);
        }
    }
}
```

**UDP vs TCP 比較：**
| 特性 | UDP | TCP |
|------|-----|-----|
| 連線 | 無連線 | 需建立連線（三次握手）|
| 可靠性 | 不保證送達 | 保證送達 |
| 速度 | 快 | 較慢 |
| 適用場景 | 影片串流、遊戲 | 網頁、檔案傳輸 |
| Java 類別 | `DatagramSocket` | `Socket` / `ServerSocket` |

---

## 本章總結

| 課號 | 主題 | 關鍵類別 |
|------|------|---------|
| Brad44 | 路徑分隔符 | `File.separator` |
| Brad45 | File 物件 | `File`, `exists()`, `getAbsolutePath()` |
| Brad46 | 建立/移動檔案 | `createNewFile()`, `renameTo()` |
| Brad47 | 寫入文字 | `FileOutputStream` |
| Brad48/49 | 讀取位元組 | `FileInputStream` |
| Brad50 | 讀取字元 | `FileReader` |
| Brad51 | 複製（慢） | 逐位元組 |
| Brad52 | 複製（快）| 緩衝區 4KB |
| Brad53/54 | 讀 CSV | `BufferedReader`, `readLine()`, `split()` |
| Brad55/56 | 序列化 | `ObjectOutputStream`, `Serializable` |
| Brad57/58 | 多物件序列化 | 繼承 + 序列化 |
| Brad59 | IP 位址 | `InetAddress` |
| Brad60/61 | UDP | `DatagramSocket`, `DatagramPacket` |
| Brad62/63 | TCP | `Socket`, `ServerSocket` |

---

## 本章練習題

### 【填充題】

1. 使用 `try-with-resources` 的好處是：離開 try 區塊後，資源會自動呼叫 `______` 方法關閉。

2. `FileOutputStream("file.txt", true)` 第二個參數 `true` 表示 `______` 模式（不覆蓋原有內容）。

3. `BufferedReader` 的 `readLine()` 方法回傳 `null` 代表 `______`。

4. 類別要能被序列化，必須實作 `______` 介面。

5. TCP 用 `______` 類別作為客戶端，用 `______` 類別在伺服器端等待連線。

---

### 【判斷題】

6. `File f = new File("abc.txt")` 這行程式碼會實際建立 abc.txt 檔案。`( )`

7. `FileInputStream` 適合讀取中文文字檔案，因為它能正確處理多位元組字元。`( )`

8. 使用緩衝區（`byte[] buf = new byte[4096]`）複製檔案比逐位元組複製速度快得多。`( )`

9. UDP 傳輸比 TCP 更可靠，因為它不需要建立連線。`( )`

10. `ObjectInputStream.readObject()` 可以還原物件，包括物件內所有欄位的值。`( )`

---

### 【選擇題】

11. 要讀取一個包含中文的 CSV 文字檔，最適合的讀取方式是？
    - A. `FileInputStream` 逐位元組讀取
    - B. `FileReader` + `BufferedReader` 逐行讀取
    - C. `ObjectInputStream` 讀取物件
    - D. `DatagramSocket` 接收資料

12. 以下哪個程式碼能正確建立 TCP 伺服器，監聽 8080 埠？
    - A. `Socket server = new Socket(8080);`
    - B. `ServerSocket server = new ServerSocket(8080);`
    - C. `DatagramSocket server = new DatagramSocket(8080);`
    - D. `InetAddress server = InetAddress.getByName(8080);`

13. `new FileOutputStream("log.txt", false)` 中，`false` 的效果是？
    - A. 不建立檔案
    - B. 覆蓋原有內容（從頭寫起）
    - C. 附加到檔案末尾
    - D. 以唯讀模式開啟

14. 以下關於物件序列化的描述，哪個是錯的？
    - A. 序列化的類別必須 `implements Serializable`
    - B. 序列化後可以把物件存到硬碟或透過網路傳送
    - C. 序列化可以儲存靜態（static）欄位的值
    - D. 反序列化能還原物件的所有實例欄位

---

### 【程式題】

**題目 A（基礎）：** 寫一個程式，讀取一個文字檔（`input.txt`），印出每一行的行號和內容，格式為 `1: 第一行內容`。

**題目 B（中等）：** 寫一個程式，統計一個文字檔中每個單字出現的次數（不分大小寫），使用 `HashMap<String, Integer>` 儲存結果，最後印出所有單字和次數。

**題目 C（中等）：** 設計一個 `Person` 類別（包含姓名、年齡欄位），讓它可以被序列化。然後建立3個 Person 物件，存入一個 ArrayList，再將整個 List 序列化存入檔案，最後從檔案讀回並印出所有人的資訊。

**題目 D（進階）：** 寫一個簡單的 TCP 通訊程式：
- Server：啟動後持續等待連線，收到訊息後印出，並回應「Echo: 原始訊息」
- Client：連線到 Server，讀取使用者輸入，送出後印出 Server 的回應

---

### 【答案】

**填充題：** 1. `close()` 2. 附加（append）3. 已到達檔案末尾 4. `Serializable` 5. `Socket` / `ServerSocket`

**判斷題：** 6. ✗（只是記錄路徑，要呼叫 `createNewFile()` 才建立）7. ✗（`FileInputStream` 是位元組流，處理多位元組字元可能出錯，應用 `InputStreamReader` 包裝）8. ✓ 9. ✗（UDP 不保證送達，TCP 才更可靠）10. ✓

**選擇題：** 11. B 12. B 13. B 14. C（static 欄位不會被序列化）
