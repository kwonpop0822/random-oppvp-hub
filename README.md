# OPPVPHub 1.0.0

## 개요

`OPPVPHub`은 Random OP PVP 게임을 전용 월드에서 운영하고, Multiverse-Core·WorldGuard·LuckPerms·CoreProtect와의 연동 상태를 한곳에서 관리하는 Paper 1.21용 운영 허브입니다. 이 배포본에는 허브 연동 API가 추가된 `RandomOPPVP 1.1.0`도 함께 포함됩니다. 기존 `RandomOPPVP 1.0.1`은 반드시 제거하고 1.1.0으로 교체해야 합니다.

## 설치 순서

먼저 Paper 1.21 서버를 종료합니다. 서버의 `plugins/` 폴더에서 기존 `RandomOPPVP-1.0.1.jar`를 제거한 뒤, `RandomOPPVP-1.1.0.jar`와 `OPPVPHub-1.0.0.jar`를 넣습니다. 권장 플러그인인 Multiverse-Core, WorldEdit, WorldGuard, LuckPerms, CoreProtect도 같은 폴더에 넣고 서버를 시작합니다.

Multiverse-Core를 사용하는 경우, 다음 예시처럼 PVP 전용 월드를 먼저 만들고 로드합니다. 허브는 월드를 생성하지 않으며, 존재하는 월드를 설정에 따라 로드만 시도합니다.

```text
/mv create oppvp normal
/mv load oppvp
```

WorldGuard를 사용할 경우에는 WorldEdit로 경기장 범위를 선택한 뒤, `oppvp_arena`라는 지역을 만듭니다. 다른 이름을 쓰려면 `plugins/OPPVPHub/config.yml`의 `worldguard.region-id`를 같은 이름으로 변경합니다.

```text
/rg define oppvp_arena
```

허브 설정 파일에서 `game.world: "oppvp"`가 실제 PVP 월드 이름과 일치하는지 확인한 후 `/oppvphub reload`를 실행하거나 서버를 재시작합니다.

## 운영 흐름

| 순서 | 관리자 동작 | 허브 동작 |
|---|---|---|
| 1 | 플레이어가 `/oppvphub join` | 설정된 PVP 월드의 스폰 지점으로 이동합니다. |
| 2 | `/oppvphub prepare` | RandomOPPVP에 게임 월드를 지정하고, 설정된 WorldGuard 경기장 지역의 PVP를 허용합니다. 기존 PVP 플래그는 메모리에 보관합니다. |
| 3 | `/oppvphub start` | 최소 인원을 검사한 뒤 서버 콘솔을 통해 RandomOPPVP 게임 시작을 요청합니다. |
| 4 | 게임 진행 | RandomOPPVP 1.1.0은 PVP 월드에 있는 참가자만 게임에 포함하고, 해당 월드 경계만 조작합니다. |
| 5 | `/oppvphub end` | 게임 종료를 요청하고, 허브가 바꿨던 WorldGuard PVP 플래그를 원래 값으로 복구합니다. |

## 명령어와 권한

| 명령어 | 권한 | 설명 |
|---|---|---|
| `/oppvphub join` | `oppvphub.join` | PVP 월드 입장. 기본적으로 모든 플레이어에게 허용됩니다. |
| `/oppvphub prepare` | `oppvphub.admin` | 월드와 WorldGuard 경기장 규칙 준비. |
| `/oppvphub start` | `oppvphub.admin` | 참가 인원을 확인하고 게임 시작. |
| `/oppvphub end` | `oppvphub.admin` | 게임 종료 및 PVP 규칙 복구. |
| `/oppvphub status` | `oppvphub.admin` | RandomOPPVP 및 선택 연동 플러그인의 로드 상태 확인. |
| `/oppvphub reload` | `oppvphub.admin` | 허브 설정과 연동 상태 새로 고침. |

LuckPerms를 쓰는 서버에서는 운영진 그룹에 `oppvphub.admin`을 부여하세요. `randomoppvp.admin`은 RandomOPPVP 직접 명령어를 쓸 운영진에게만 필요하며, 일반적인 운영은 `/oppvphub` 명령어만으로 가능합니다.

## 선택적 연동 범위

| 플러그인 | 허브 동작 | 미설치 시 동작 |
|---|---|---|
| Multiverse-Core | 설정된 월드가 Bukkit에 로드되지 않았으면 `mv load <world>` 명령을 시도합니다. | 이미 Bukkit에 로드된 월드는 정상적으로 사용합니다. |
| WorldGuard | 지정 지역의 PVP 플래그를 게임 준비 시 `ALLOW`로 바꾸고, 종료 또는 허브 비활성화 시 직전 값으로 복구합니다. | PVP 플래그 변경만 건너뛰며 게임은 계속 가능합니다. |
| LuckPerms | Bukkit 권한 노드인 `oppvphub.admin`, `oppvphub.join`의 그룹별 배정을 관리할 수 있습니다. | OP와 Paper 기본 권한 체계로 동작합니다. |
| CoreProtect | 플러그인과 API 버전을 상태 화면·운영 감사 로그에서 확인합니다. 자동 롤백은 관리자의 명시적 확인 없이 월드를 바꾸지 않도록 의도적으로 제공하지 않습니다. | 감사 로그만 일반 서버 로그에 기록합니다. |

> 허브가 관리하는 WorldGuard 변경 사항은 서버 메모리에 보관됩니다. 따라서 서버가 강제 종료된 뒤 재시작되면 자동 복구할 이전 플래그 값이 남지 않습니다. 월드·보호 설정을 바꾸기 전에는 `/oppvphub end`로 게임을 정상 종료하세요.

## 검증

이 배포본은 OpenJDK 21과 Maven에서 `mvn clean package`로 빌드했습니다. 두 JAR의 `plugin.yml`, `config.yml`, 메인 클래스 및 WorldGuard/CoreProtect 연동 클래스를 확인했습니다. 실제 서버 실행 테스트는 서버 월드·권한·다른 플러그인 설정에 영향을 주므로, 운영 서버에 올리기 전 별도 테스트 월드에서 `/oppvphub status`, `/oppvphub prepare`, `/oppvphub start`, `/oppvphub end` 순서로 점검하세요.

## 참고 자료

- [Multiverse-Core](https://modrinth.com/plugin/multiverse-core)
- [WorldGuard API 의존성 문서](https://worldguard.enginehub.org/en/latest/developer/dependency/)
- [LuckPerms 개발자 API](https://luckperms.net/wiki/Developer-API)
- [CoreProtect API](https://docs.coreprotect.net/api/)
