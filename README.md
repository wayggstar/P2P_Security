# P2P Security

P2P Security는 **Minecraft P2P 및 싱글플레이어 호스팅 멀티플레이 환경을 위한 보안·관리 모드**입니다.

Minecraft의 기본 OP 시스템은 강력한 관리 권한을 제공하지만, 플레이어별 세부 권한 관리, 행동 기록, 영구 제재, 그리핑 복구 등의 기능은 제한적입니다.

P2P Security는 이러한 문제를 해결하기 위해 기존 OP 시스템과 별도로 동작하는 보안 계층을 제공합니다.

주요 목표는 다음과 같습니다.

- 역할 기반 플레이어 권한 관리
- 플레이어별 세부 권한 설정
- 영구 차단 및 긴급 잠금
- 플레이어 행동 기록 및 조사
- 안전한 그리핑 롤백
- 월드 백업 및 자동 백업

> **현재 상태: Alpha**
>
> 아직 개발 및 테스트 중인 프로젝트입니다. 중요한 월드에 적용하기 전에 별도의 테스트 월드에서 충분히 검증하는 것을 권장합니다.

---

# 주요 기능

## 1. 역할 기반 권한 관리

플레이어에게 세 가지 역할을 지정할 수 있습니다.

| 역할 | 설명 |
|---|---|
| `visitor` | 대부분의 행동이 제한된 방문자 |
| `member` | 일반적으로 신뢰된 플레이어 |
| `moderator` | 일반 플레이 + 조사 기능을 사용할 수 있는 관리자 |

월드 방장은 별도의 **Owner**로 취급됩니다.

Owner는 일반적인 역할 변경이나 제재 대상이 될 수 없습니다.

P2P Security의 역할 및 권한 시스템은 Minecraft의 기본 OP 시스템과 별도로 관리됩니다.

### 역할 설정

```text
/p2ps role <플레이어> <역할>
```

예:

```text
/p2ps role Steve member
/p2ps role Alex moderator
```

---

# 2. 플레이어별 세부 권한

역할의 기본 권한과 별개로 특정 플레이어의 권한을 직접 허용하거나 차단할 수 있습니다.

현재 지원하는 Action:

| Action | 설명 |
|---|---|
| `BREAK` | 블록 파괴 |
| `PLACE` | 블록 설치 |
| `USE` | 블록 상호작용 |
| `ITEM` | 아이템 사용 |
| `ATTACK` | 엔티티 공격 |
| `ENTITY` | 엔티티 상호작용 |
| `HAZARD` | 위험 행동 |
| `AUDIT` | 조사 기능 |
| `ROLLBACK` | 롤백 기능 |

각 권한에는 다음 세 가지 상태를 지정할 수 있습니다.

```text
allow
deny
unset
```

`unset`을 사용하면 개별 설정을 제거하고 다시 역할의 기본 권한을 따릅니다.

### 예시

Steve는 `member`이지만 블록 파괴만 금지:

```text
/p2ps permit Steve BREAK deny
```

다시 member 기본 설정으로 복구:

```text
/p2ps permit Steve BREAK unset
```

특정 플레이어에게 조사 권한 추가:

```text
/p2ps permit Steve AUDIT allow
```

---

# 3. 플레이어 이름 / UUID 지원

관리 명령어에서는 플레이어의 **이름 또는 UUID**를 사용할 수 있습니다.

예:

```text
/p2ps role Steve member
```

또는:

```text
/p2ps role 12345678-1234-1234-1234-123456789abc member
```

온라인 플레이어는 **Tab 자동완성**을 지원합니다.

자동완성 목록에는 플레이어 이름이 표시되며 추가 정보로 UUID를 확인할 수 있습니다.

보안 데이터는 플레이어 이름이 아닌 **UUID를 기준으로 저장**됩니다.

따라서 플레이어가 Minecraft 닉네임을 변경하더라도 내부 보안 식별자는 유지됩니다.

---

# 4. 행동 기록 / Audit

플레이어가 월드에서 수행한 변경 사항을 기록하여 그리핑 조사 및 롤백에 사용할 수 있습니다.

## 플레이어 기록 조회

```text
/p2ps lookup <플레이어>
```

예:

```text
/p2ps lookup Steve
```

현재 Alpha 버전에서는 최근 **20,000개의 이벤트**를 대상으로 검색하며, 해당 플레이어의 최근 기록을 최대 15개까지 표시합니다.

---

## 블록 조사 모드

```text
/p2ps inspect
```

조사 모드를 활성화한 상태에서 블록을 좌클릭 또는 우클릭하면 해당 블록과 관련된 기록을 확인할 수 있습니다.

다시 실행하면 조사 모드가 종료됩니다.

```text
/p2ps inspect
```

---

# 5. 안전한 Rollback

P2P Security는 그리핑 복구를 위한 롤백 기능을 제공합니다.

실수로 대규모 월드 변경이 발생하는 것을 방지하기 위해 롤백은 **Preview → Apply** 두 단계로 실행됩니다.

## Step 1 — Preview

```text
/p2ps preview <플레이어> <분> <반경>
```

예:

```text
/p2ps preview Steve 10 20
```

현재 위치를 기준으로:

- Steve가
- 최근 10분 동안
- 반경 20블록 안에서

수행한 변경 사항을 검색합니다.

Preview 단계에서는 **월드를 변경하지 않습니다.**

대신 적용 가능한 변경 사항을 계산하고 롤백 확인용 Token을 생성합니다.

---

## Step 2 — Apply

Preview에서 생성된 Token을 사용합니다.

```text
/p2ps apply <token>
```

이 명령을 실행해야 실제 월드 변경이 이루어집니다.

---

## Rollback 충돌 감지

P2P Security는 과거 상태를 무조건 덮어쓰지 않습니다.

예를 들어:

```text
Steve가 STONE 설치
        ↓
다른 플레이어가 해당 위치를 변경
        ↓
현재 DIAMOND_BLOCK
        ↓
Steve의 행동 Rollback
```

단순 롤백 시스템이라면 `DIAMOND_BLOCK`까지 삭제할 수 있습니다.

P2P Security는 롤백 대상의 예상 현재 상태와 실제 현재 상태가 다르면 이를 **Conflict**로 판단하여 해당 변경을 건너뜁니다.

이를 통해 다른 플레이어가 이후에 수행한 정상적인 변경을 최대한 보호합니다.

---

# 6. Rollback Undo

가장 최근에 실행한 롤백은 되돌릴 수 있습니다.

```text
/p2ps undo
```

잘못된 롤백을 실행했을 경우 복구하기 위한 안전장치입니다.

---

# 7. 영구 차단

플레이어를 월드에서 영구 차단할 수 있습니다.

```text
/p2ps ban <플레이어> <사유>
```

예:

```text
/p2ps ban Steve griefing
```

플레이어가 현재 접속 중이라면 즉시 연결이 종료됩니다.

차단 정보는 메모리가 아닌 저장소에 기록되므로 **월드를 종료하고 다시 실행한 이후에도 유지**됩니다.

## 차단 목록

```text
/p2ps bans
```

현재 저장된 차단 목록을 확인합니다.

## 차단 해제

```text
/p2ps unban <플레이어 또는 UUID>
```

---

# 8. 긴급 잠금

문제가 발생했을 때 월드를 즉시 잠글 수 있습니다.

```text
/p2ps lock true
```

긴급 잠금이 활성화되면 Owner가 아닌 플레이어들의 연결을 종료합니다.

잠금 해제:

```text
/p2ps lock false
```

이 기능은 대규모 그리핑이나 보안 사고가 발생했을 때 추가 피해를 빠르게 차단하기 위한 기능입니다.

---

# 9. 월드 백업

현재 월드를 수동으로 백업할 수 있습니다.

```text
/p2ps backup
```

백업 생성 후 파일의 기본 구조와 무결성을 확인하여 잘못된 백업이 정상적으로 생성된 것처럼 처리되는 것을 방지합니다.

---

# 10. 자동 백업

일정 시간마다 월드를 자동으로 백업할 수 있습니다.

```text
/p2ps autobackup <분>
```

예:

```text
/p2ps autobackup 30
```

30분마다 자동 백업을 생성합니다.

자동 백업 비활성화:

```text
/p2ps autobackup 0
```

---

# 명령어 정리

| 명령어 | 설명 |
|---|---|
| `/p2ps` | 사용 가능한 주요 명령어 표시 |
| `/p2ps status` | P2P Security 상태 확인 |
| `/p2ps players` | 현재 접속 중인 플레이어와 UUID 확인 |
| `/p2ps role <player> <role>` | 플레이어 역할 설정 |
| `/p2ps permit <player> <action> <decision>` | 개별 권한 설정 |
| `/p2ps ban <player> <reason>` | 플레이어 영구 차단 |
| `/p2ps unban <player>` | 차단 해제 |
| `/p2ps bans` | 차단 목록 확인 |
| `/p2ps lock <true/false>` | 긴급 잠금 설정 |
| `/p2ps inspect` | 블록 조사 모드 |
| `/p2ps lookup <player>` | 플레이어 행동 기록 조회 |
| `/p2ps preview <player> <minutes> <radius>` | 롤백 Preview 생성 |
| `/p2ps apply <token>` | 롤백 실행 |
| `/p2ps undo` | 최근 롤백 취소 |
| `/p2ps backup` | 월드 수동 백업 |
| `/p2ps autobackup <minutes>` | 자동 백업 설정 |

---

# 보안 설계 원칙

P2P Security는 기능의 편리함뿐만 아니라 **실패 상황에서의 안전성**을 중요하게 생각합니다.

### Fail Closed

보안 설정 데이터를 정상적으로 읽을 수 없는 경우 잘못된 기본 설정으로 초기화하여 접근을 허용하는 대신, 안전한 방향으로 동작하도록 설계합니다.

### UUID 기반 식별

닉네임은 변경될 수 있으므로 플레이어의 보안 식별에는 UUID를 사용합니다.

### Preview Before Rollback

Rollback은 즉시 실행되지 않습니다.

먼저 Preview를 생성하고 Token을 통해 명시적으로 적용해야 합니다.

### Conflict-Aware Rollback

과거 변경 사항을 무조건 덮어쓰지 않습니다.

이후 다른 플레이어가 수정한 블록은 충돌로 판단하여 보존합니다.

### Persistent Security Data

Ban, Role, Permission과 같은 보안 정보는 월드 실행 중의 메모리에만 의존하지 않고 별도의 영구 데이터로 관리합니다.

---

# 현재 Alpha의 제한 사항

현재 버전은 초기 개발 단계이므로 다음과 같은 제한 사항이 있습니다.

- Audit은 최근 20,000개의 이벤트를 중심으로 동작합니다.
- Rollback이 모든 Minecraft 상태 및 복잡한 컨테이너를 완벽하게 복원하는 것은 아닙니다.
- 플레이어 이름 자동완성은 현재 온라인 플레이어를 중심으로 동작합니다.
- 대규모 서버 환경을 대상으로 최적화된 시스템은 아닙니다.
- 기능 및 저장 형식은 개발 과정에서 변경될 수 있습니다.

중요한 월드에서는 반드시 별도의 백업을 유지하는 것을 권장합니다.

---

# 향후 개발 계획

현재 고려 중인 기능:

- SQLite 기반 Audit 저장소
- 플레이어 / 시간 / 위치 / 행동별 Audit 검색
- 오프라인 플레이어 이름 자동완성
- UUID ↔ Last Known Name 저장
- 더 세분화된 Permission 시스템
- Rollback 기능 확장
- TNT 및 폭발 원인 추적
- 플레이어 행동 간 Causal Chain 추적
- Backup 관리 개선
- 관리자 UI
- Audit / Rollback 시각화

예를 들어 향후에는 다음과 같은 흐름을 추적하는 것을 목표로 합니다.

```text
Player
  ↓
TNT 설치
  ↓
TNT Entity 생성
  ↓
폭발
  ↓
Block Damage
```

이를 통해 단순히 "어떤 블록이 파괴되었는가"뿐만 아니라 **누구의 어떤 행동으로 인해 해당 피해가 발생했는지** 추적하고 선택적으로 복구할 수 있는 시스템을 목표로 합니다.

---

# 개발 목적

P2P Security는 단순한 Minecraft 관리 명령어 모음이 아니라, P2P 멀티플레이 환경에서 발생할 수 있는 다음 문제를 다루는 것을 목표로 합니다.

```text
Authentication / Identity
        ↓
Authorization
        ↓
Audit
        ↓
Incident Detection
        ↓
Containment
        ↓
Recovery
```

즉,

**"누가 무엇을 할 수 있는가?"**

뿐만 아니라,

**"누가 무엇을 했고, 문제가 발생했을 때 어떻게 안전하게 복구할 것인가?"**

까지 다루는 보안 시스템을 만드는 것이 프로젝트의 목표입니다.
