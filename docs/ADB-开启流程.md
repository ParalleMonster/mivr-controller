# 小米 VR 一体机开启 ADB 流程

> 本文仅用于你本人/单位持有并授权的 Mi VR 测试设备。  
> 文档中的头显序列号、BLE 地址、设备 secret 均已替换为占位符。  
> 不要把真实的 device_info.secret 提交到仓库或发给他人。  

> 适用：小米 VR 一体机（Mi VR Standalone，`v1o` / `pacific`），测试/维护用途。  
> 本文记录的是 2026-11-11 在测试机上实际跑通的流程，不是通用一键工具。  
> 目标：开启 **开发者模式 + USB ADB**。不是 root，不是解 bootloader。

---

## 0. 本次实测基线

```text
设备序列号    <HEADSET_SERIAL>
型号          v1o / pacific
系统          Android 7.1.2 (API 25)
Build         N2G48H / 840
安全补丁      2018-04-05
Bootloader    locked / verified boot green
SELinux       Enforcing
```

开启后：

```text
adb_enabled=1
vrmode_developer_mode=1
adb shell uid=2000(shell)
```

`adb root` 仍然失败：

```text
adbd cannot run as root in production builds
```

---

## 1. 前置条件

### 1.1 电脑

- Windows 10/11。
- 已解压 Android SDK Platform-Tools，目录中有 `adb.exe`。
- 能用的 USB 数据线（不是只能充电的线）。

本文示例把 platform-tools 放在：

```text
.toolchain\official-adb\platform-tools\adb.exe
```

如果你放在别处，把命令里的路径替换掉即可。

### 1.2 已配对的 Root 手机

这是关键条件。需要一台：

- 已安装 **小米VR** App（`com.mi.dlabs.vr.thor`，本次为 3.1.2）；
- 已登录小米账号，并已与目标头显完成配对/绑定；
- 已 Root，`su` 可用；
- 能通过 USB ADB 连接到电脑。

原因：头显的 BLE Companion 服务要求一个“已绑定设备 secret”，这个 secret 存在手机 App 的私有数据库里，普通权限读不到。

### 1.3 头显

- 正常开机，不要恢复出厂设置。
- 蓝牙开启，放在电脑附近。
- 最好暂时关闭手机上其他正在占用头显 BLE 的连接（小米VR App 可以留着，脚本会重新连）。

---

## 2. 总体流程

```text
确认 adb 工具
  → 从已配对手机取 device_info.secret
  → 扫描头显 BLE 地址
  → XIAOMI_AUTHENTICATE 换 session token
  → DEV_MODE_SET 设为 1
  → DEV_MODE_STATUS 复核
  → USB ADB 验证
```

不需要刷机、不需要 OTA 包、不需要改分区。

---

## 3. 确认 ADB 工具可用

先进入工具目录（路径按本机实际情况替换）：

```powershell
cd <WORK_DIR>
$a = "<REPO_ROOT>\.toolchain\official-adb\platform-tools\adb.exe"
& $a version
& $a devices -l
```

应能看到 `Android Debug Bridge version ...`。

如果提示找不到 adb，先把 platform-tools 解压到上述路径，或改 `$a`。

> 后续的 `mivr_auth.py`、`ble_scan.py` 都在这个 `work` 目录下。  
> 如果你的 `python` 不在 PATH，把命令里的 `python` 换成实际的 `python.exe` 路径。

---

## 4. 从已配对手机取设备 secret

### 4.1 确认手机已连接

```powershell
& $a devices -l
```

记下手机的 serial，例如：

```text
<PHONE_SERIAL>    device ...
```

下文用 `<PHONE_SERIAL>` 代替。

### 4.2 复制数据库到头显/手机的下载目录

```powershell
& $a -s <PHONE_SERIAL> shell su -c "cp /data/data/com.mi.dlabs.vr.thor/databases/MiVR_DeviceInfo.db /sdcard/Download/"
```

如果 `su` 弹授权，手机上点允许。

### 4.3 拉到电脑

```powershell
& $a -s <PHONE_SERIAL> pull /sdcard/Download/MiVR_DeviceInfo.db .
```

### 4.4 读出 secret

有 `sqlite3` 时：

```powershell
sqlite3 MiVR_DeviceInfo.db "select deviceId, btAddress, secret from device_info;"
```

没有 `sqlite3`，用 Python：

```powershell
python -c "import sqlite3; print(sqlite3.connect('MiVR_DeviceInfo.db').execute('select deviceId, btAddress, secret from device_info').fetchall())"
```

输出类似：

```text
[('<HEADSET_SERIAL>', '<HEADSET_BLE_ADDRESS>', '<DEVICE_SECRET>')]
```

- `deviceId` 应等于头显序列号；
- `btAddress` 是头显 BLE 地址；
- `secret` 就是下一步要用的凭据。

> **secret 等同于这台头显的配对凭据。** 不要贴到公开仓库、聊天记录或截图里。本文不保存明文。  
> 读完建议删除电脑上的 `MiVR_DeviceInfo.db`。

---

## 5. 扫描头显 BLE 地址

头显正常广播名类似 `v1o-XXXX`，服务 UUID：

```text
0000feb8-0000-1000-8000-00805f9b34fb
```

运行附带的扫描脚本：

```powershell
python .\ble_scan.py
```

在输出里找：

```json
{"address": "<HEADSET_BLE_ADDRESS>", "name": "v1o-XXXX", ...}
```

地址可能变化，**以本次扫描结果为准**，不要死用旧地址。

---

## 6. 认证并查询当前状态

先只查询，不修改：

```powershell
python .\mivr_auth.py --address <HEADSET_BLE_ADDRESS> --secret "<DEVICE_SECRET>"
```

正常输出：

```text
Connected <HEADSET_BLE_ADDRESS>
auth_code 0 auth_seq 1
token_ok 64
status_code 0 status_seq 2
status_body {"1": 0}
```

含义：

- `auth_code 0`：`XIAOMI_AUTHENTICATE` 成功；
- `token_ok 64`：拿到了 64 字符会话 token；
- `status_body {"1": 0}`：当前 ADB/开发者模式为关闭。

如果 `auth_code` 不是 0，见第 9 节。

---

## 7. 开启 ADB

```powershell
python .\mivr_auth.py --address <HEADSET_BLE_ADDRESS> --secret "<DEVICE_SECRET>" --enable
```

正常输出：

```text
auth_code 0 auth_seq 1
token_ok 64
status_code 0 status_seq 2
status_body {"1": 0}
set_code 0 set_seq 3
set_body {}
status2_code 0 status2_seq 4
status2_body {"1": 1}
```

`set_code 0` 表示 `DEV_MODE_SET` 成功，`status2_body {"1": 1}` 表示复核为开启。

---

## 8. USB ADB 验证

插上头显 USB 数据线：

```powershell
& $a devices -l
```

应出现：

```text
<HEADSET_SERIAL>    device product:v1o model:v1o device:pacific
```

进一步验证：

```powershell
& $a -s <HEADSET_SERIAL> shell id
& $a -s <HEADSET_SERIAL> shell settings get global adb_enabled
& $a -s <HEADSET_SERIAL> shell settings get global vrmode_developer_mode
```

预期：

```text
uid=2000(shell) ... context=u:r:shell:s0
1
1
```

如果第一次连接弹“允许 USB 调试吗？”，戴上头显勾选“始终允许”，再点确定。

---

## 9. 失败排查

### 9.1 `auth_code 3` / AUTH_FAIL

说明 secret 不对或已失效。

按顺序检查：

1. 手机 App 是否还是同一个小米账号；
2. 头显是否被重新配对/恢复过；
3. `MiVR_DeviceInfo.db` 是否是当前这台头显的记录（`deviceId` 对得上）；
4. 是否拿错设备的 secret；
5. 重新在小米VR App 里配对一次，再读数据库。

### 9.2 扫描不到 `v1o-...`

- 头显是否开机、亮屏；
- 蓝牙是否开启；
- 头显是否已被手机 App 占用连接，先关掉 App 再扫；
- 地址可能变了，重新扫描，不要用旧地址；
- 不要为了这个恢复出厂设置。

### 9.3 `adb devices` 为空

- 换一根**能传数据**的 USB 线；
- 换 USB 口，避免只供电的 Hub；
- Windows 设备管理器看是否有 Android/ADB 设备；
- 头显端是否弹出授权；
- 重启 adb：

```powershell
& $a kill-server
& $a start-server
& $a devices -l
```

### 9.4 显示 `unauthorized`

戴上头显，点“允许 USB 调试”。  
如果没弹窗，可在开发者选项里撤销 USB 调试授权，再重新插线。

### 9.5 `adb root` 失败

这是正常的：

```text
adbd cannot run as root in production builds
```

本流程只开 ADB shell，不给 root。不要因为这条失败去刷 boot 或改分区。

### 9.6 应用启动时报 `IVRService.relFreq()` / `SecurityException`

这是小米 VRService 的**应用白名单/权限**问题，不是 ADB 没开。  
需要单独处理应用包名/白名单，和本文的 ADB 开启流程无关。

---

## 10. 重启后是否保留

`DEV_MODE_SET` 写入的是：

```text
Settings.Global.adb_enabled = 1
Settings.Global.vrmode_developer_mode = 1
```

正常重启应保留。建议重启后复核：

```powershell
& $a reboot
Start-Sleep -Seconds 30
& $a devices -l
& $a -s <HEADSET_SERIAL> shell settings get global adb_enabled
```

如果重启后 ADB 又关闭，重新执行第 7 节即可；不要恢复出厂。

---

## 11. 关闭 ADB

测试结束后：

```powershell
python .\mivr_auth.py --address <HEADSET_BLE_ADDRESS> --secret "<DEVICE_SECRET>" --disable
```

预期：

```text
set_code 0
status2_body {"1": 0}
```

关闭后 USB ADB 会断开，这是正常的。

---

## 12. 安全与清理

- `device_info.secret` 是配对凭据，不要提交到 Git、不要发群、不要截图。
- 用完删除电脑上的 `MiVR_DeviceInfo.db`、临时日志和 shell 历史。
- 不要把本文的 secret 占位符替换成真实值后保存到共享目录。
- 不要恢复出厂设置：小米 VR 的 App/配对服务已不稳定，恢复后可能无法重新激活。
- 这是测试机维护流程；不要用于他人的设备或绕过账号/防盗锁。

---

## 13. 协议参考（排障用）

BLE：

```text
Service          0000feb8-0000-1000-8000-00805f9b34fb
Characteristic   7a442881-509c-47fa-ac02-b06a37d9eb76
```

请求 protobuf：

```text
field 1  version (0)
field 2  method
field 3  sequence
field 4  body
field 5  token (认证后带上)
```

方法：

```text
3     XIAOMI_AUTHENTICATE   body field 1 = secret；响应 body field 1 = token
6002  DEV_MODE_STATUS       body 为空；响应 body field 1 = 0/1
6001  DEV_MODE_SET          body field 1 = 0/1
```

---

## 14. 文件

本仓库中的脚本位于：

```text
tools/adb/mivr_auth.py
tools/adb/ble_scan.py
tools/adb/requirements.txt
```

- `mivr_auth.py`：认证、查询、开启/关闭 ADB。
- `ble_scan.py`：扫描 `v1o-*` BLE 广播。
- `requirements.txt`：Python 依赖（`bleak`）。

运行前安装依赖：

```powershell
python -m pip install -r .\tools\adb\requirements.txt
```

脚本会优先使用同目录的 `python-deps/`，没有则使用当前 Python 环境。  
`SUCCESS.md` 是本地实测记录，不随本流程上传。