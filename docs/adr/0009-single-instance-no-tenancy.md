# ADR 0009: 한 설치 인스턴스에서 한 조직 지원

- Status: Accepted
- Date: 2026-08-10

## Context

초기 제품은 조직이 직접 설치하는 고객지원 시스템입니다. 여러 회사가 함께 사용하는 SaaS 운영은 이후 별도 제품 결정으로 다룹니다.

## Decision

하나의 배포와 DB가 하나의 지원 조직을 나타냅니다. 각 모델에 `tenant_id`, workspace routing, tenant별 캐시와 관리 기능을 추가하지 않습니다.

## Consequences

- schema와 권한 모델을 단순하게 유지합니다.
- SaaS를 제공하려면 제품과 아키텍처를 함께 검토해야 합니다. 설정 하나만 바꿔 제공하는 기능으로 두지 않습니다.
- ADR 0049의 서버 지정 `workspaceKey`는 AI 배포와 예산을 구분하는 값으로 사용합니다. 고객 조직이나 SaaS tenant 모델과는 별개입니다.
