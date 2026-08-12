#!/usr/bin/env python3
"""Add Korean API-reference text and safe synthetic examples to committed OpenAPI contracts."""
from __future__ import annotations

import re
from pathlib import Path
from typing import Any

import yaml


ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = (
    ROOT / "api/core-api-outline-v1.yaml",
    ROOT / "api/customer-identity-api-v1.yaml",
    ROOT / "api/platform-api-outline-v1.yaml",
)

OPERATION_SUMMARIES = {
    "createCustomerRequest": "고객 문의 접수",
    "getAnonymousRequest": "접근 토큰으로 고객 문의 조회",
    "addCustomerRequestComment": "고객 문의에 공개 답변 추가",
    "listCustomerRequests": "인증 고객의 문의 목록 조회",
    "getCustomerAccessMode": "고객 문의 접근 정책 조회",
    "getStaffCsrfToken": "직원 세션용 CSRF 토큰 발급",
    "createStaffSession": "직원 로그인 세션 생성",
    "deleteStaffSession": "현재 직원 세션 종료",
    "getCurrentStaff": "현재 직원과 권한 조회",
    "listAgentViews": "상담사가 사용할 수 있는 티켓 보기 조회",
    "listTicketsInView": "선택한 보기의 티켓 목록 조회",
    "createAgentTicket": "상담사 발신 티켓 생성",
    "getAgentTicket": "상담사용 티켓 상세 조회",
    "updateAgentTicket": "티켓 변경 사항을 하나의 명령으로 저장",
    "transferAgentTicket": "기존 티켓의 담당 그룹 또는 상담사 이관",
    "createChildTicket": "내부 협업용 자식 티켓 생성",
    "listAgentTicketExternalReferences": "티켓에 연결된 외부 참조 조회",
    "createAgentTicketExternalReference": "티켓에 외부 시스템 객체 연결",
    "deleteAgentTicketExternalReference": "티켓의 외부 참조 연결 해제",
    "listTicketAudits": "티켓 변경 이력 조회",
    "searchAgentWorkspace": "상담사가 접근 가능한 티켓 검색",
    "listStaffAccounts": "직원 계정 목록 조회",
    "createStaffAccount": "직원 계정 생성",
    "disableStaffAccount": "직원 계정 비활성화",
    "grantStaffAuditAuthority": "보안 감사 세부 권한 부여",
    "revokeStaffAuditAuthority": "보안 감사 세부 권한 회수",
    "listGroups": "지원 그룹 목록 조회",
    "createGroup": "지원 그룹 생성",
    "updateGroup": "지원 그룹 이름 변경",
    "disableGroup": "사용하지 않는 지원 그룹 비활성화",
    "listGroupMembers": "지원 그룹의 활성 구성원 조회",
    "createGroupMembership": "지원 그룹에 직원 추가",
    "deleteGroupMembership": "지원 그룹에서 직원 제거",
    "listIntegrationClients": "연동 클라이언트 목록 조회",
    "createIntegrationClient": "연동 클라이언트와 최초 API 키 발급",
    "getIntegrationClient": "연동 클라이언트 상세 조회",
    "disableIntegrationClient": "연동 클라이언트 즉시 비활성화",
    "revokeIntegrationClient": "연동 클라이언트와 자격 증명 영구 폐기",
    "rotateIntegrationClientCredential": "연동 API 키 교체",
    "listAdminExternalSystems": "외부 시스템 등록 목록 조회",
    "createExternalSystem": "외부 시스템과 허용 호스트 등록",
    "updateExternalSystem": "외부 시스템 설정 변경",
    "getAdminSetting": "관리자 설정 조회",
    "updateAdminSetting": "관리자 설정 변경",
    "listAuditActivities": "통합 감사 활동 검색",
    "getAuditActivity": "감사 활동 상세 조회",
    "revealAuditSearchQuery": "보호된 검색어 원문 공개",
    "createAuditExport": "감사 활동 내보내기 요청",
    "getAuditExport": "감사 내보내기 작업 상태 조회",
    "rebuildAuditActivityProjection": "감사 활동 조회 프로젝션 재구축",
    "getCurrentCustomer": "현재 인증 고객 조회",
    "getCustomerRequest": "인증 고객의 문의 상세 조회",
    "addAuthenticatedCustomerComment": "인증 고객의 공개 답변 추가",
    "getCustomerAccessModeSetting": "고객 접근 모드 설정 조회",
    "updateCustomerAccessModeSetting": "고객 접근 모드 설정 변경",
    "listBusinessSchedules": "영업 시간표 목록 조회",
    "createBusinessSchedule": "영업 시간표 생성",
    "previewBusinessSchedule": "저장 전 영업 시간표 검증 및 미리보기",
    "getBusinessSchedule": "영업 시간표 최신 버전 조회",
    "listBusinessScheduleVersions": "영업 시간표 버전 이력 조회",
    "createBusinessScheduleVersion": "영업 시간표 새 버전 생성",
    "activateBusinessScheduleVersion": "영업 시간표 버전 활성화",
    "listSlaPolicies": "최초 답변 SLA 정책 목록 조회",
    "createSlaPolicy": "최초 답변 SLA 정책 생성",
    "previewFirstReplySlaPolicy": "최초 답변 SLA 정책 적용 결과 미리보기",
    "getSlaPolicy": "최초 답변 SLA 정책 최신 버전 조회",
    "listSlaPolicyVersions": "최초 답변 SLA 정책 버전 이력 조회",
    "createSlaPolicyVersion": "최초 답변 SLA 정책 새 버전 생성",
    "activateSlaPolicyVersion": "최초 답변 SLA 정책 버전 활성화",
    "getFirstReplySlaAnalytics": "최초 답변 SLA 성과 집계 조회",
    "requestCustomerMagicLink": "고객 로그인용 일회성 링크 요청",
    "consumeCustomerMagicLink": "일회성 링크로 고객 세션 생성",
    "getCustomerCsrfToken": "고객 세션용 CSRF 토큰 발급",
    "deleteCustomerSession": "현재 고객 세션 종료",
    "claimAnonymousCustomerRequest": "익명 문의를 인증 고객 계정에 연결",
    "issueAnonymousRequestClaimGrant": "문의 접근 토큰을 단기 연결 권한으로 교환",
    "platformCreateTicket": "외부 시스템에서 고객 문의 또는 내부 티켓 생성",
    "platformGetTicket": "연동용 티켓 상세 조회",
    "platformUpdateTicket": "외부 시스템에서 허용된 티켓 필드 변경",
    "platformAddInternalComment": "외부 시스템에서 내부 메모 추가",
}

TAG_DESCRIPTIONS = {
    "Customer Requests": "로그인 없이 문의를 접수하고 접근 토큰으로 공개 대화만 확인하는 API입니다.",
    "Customer Portal": "인증된 고객이 자신의 문의와 PUBLIC 댓글만 조회·작성하는 API입니다.",
    "Customer Identity": "이메일 매직 링크, 고객 세션, CSRF, 익명 문의 연결을 처리하는 API입니다.",
    "Customer Auth": "고객 인증 상태와 세션을 처리하는 API입니다.",
    "Staff Session": "직원 로그인 세션과 CSRF 토큰을 관리하는 API입니다.",
    "Agent Views": "상담사가 접근 가능한 티켓 큐와 저장된 보기를 조회하는 API입니다.",
    "Agent Tickets": "상담사용 티켓 조회·변경·이관·내부 협업 API입니다.",
    "Agent Search": "권한 범위 안의 티켓을 검색하고 검색 감사를 남기는 API입니다.",
    "Admin": "직원, 그룹, 권한과 제품 설정을 관리하는 ADMIN 전용 API입니다.",
    "Integration Admin": "연동 클라이언트·외부 시스템·외부 참조를 관리하는 API입니다.",
    "Audit": "감사 활동 조회·보호 콘텐츠 공개·내보내기를 처리하며 조회 자체도 감사하는 API입니다.",
    "Admin Schedule": "버전이 고정된 영업 시간표를 생성·검증·활성화하는 API입니다.",
    "Admin SLA": "최초 답변 SLA 정책의 불변 버전을 생성·검증·활성화하는 API입니다.",
    "Analytics": "감사 가능한 불변 사실을 기준으로 SLA 성과를 집계하는 API입니다.",
    "Platform Tickets": "사설망의 scoped API key를 사용하는 외부 시스템용 티켓 API입니다.",
}

DESCRIPTION_TRANSLATIONS = {
    "Browser clients omit the header only while establishing and verifying a new session. The dual-use getStaffCsrfToken operation accepts the optional header: pre-sign-in callers omit it, while an authenticated unsafe workflow sends the same actor snapshot that will guard its mutation. Missing headers remain compatible with non-browser clients. A present header must be one canonical UUID value. Mismatch fails closed without selecting the supplied actor, invalidating the server session, renewing last activity, or entering the target controller.":
        "브라우저는 새 세션을 만들고 확인하는 동안에만 이 헤더를 생략합니다. getStaffCsrfToken은 로그인 전에는 생략하고 인증 후 변경 요청에는 동일한 actor 값을 보내는 이중 용도 API입니다. 비브라우저 클라이언트는 헤더를 생략할 수 있지만, 전달할 때는 하나의 정규 UUID여야 합니다. 불일치는 제공된 actor 선택, 세션 무효화, 활동 시각 갱신 또는 Controller 진입 없이 실패로 처리됩니다.",
    "Local development": "로컬 개발 서버",
    "Always `no-store` because this capability-protected surface can contain customer data.":
        "고객 데이터가 포함될 수 있는 capability 보호 응답이므로 항상 `no-store`를 사용합니다.",
    "Always `no-store` because the response contains a one-time access token.":
        "응답에 한 번만 제공되는 접근 토큰이 포함되므로 항상 `no-store`를 사용합니다.",
    "Customer-safe request URL; the access token is not embedded.":
        "접근 토큰을 포함하지 않는 고객용 안전한 문의 URL입니다.",
    "Always `no-store` because the response contains customer content.":
        "응답에 고객 콘텐츠가 포함되므로 항상 `no-store`를 사용합니다.",
    "HttpOnly staff session cookie; Secure in production and SameSite=Lax.":
        "HttpOnly 직원 세션 쿠키이며 운영 환경에서는 Secure, SameSite=Lax를 사용합니다.",
    "HttpOnly; SameSite=Lax; Secure in production; idle 60 minutes and absolute 12 hours.":
        "HttpOnly, SameSite=Lax 쿠키이며 운영 환경에서는 Secure를 사용합니다. 유휴 만료는 60분, 절대 만료는 12시간입니다.",
    "Parent ticket ETag after the relation audit is committed.":
        "관계 감사가 커밋된 뒤의 부모 티켓 ETag입니다.",
    "Always no-store because the result is a protected staff projection.":
        "결과가 보호된 직원용 projection이므로 항상 no-store를 사용합니다.",
    "HttpOnly server session. Browser requests also use the optional ExpectedStaffActorHeader consistency guard; that header is not a credential and never supplies the authenticated actor.":
        "HttpOnly 서버 세션입니다. 브라우저 요청은 선택적인 ExpectedStaffActorHeader 일관성 guard도 사용하지만, 이 헤더는 자격 증명이 아니며 인증 actor를 결정하지 않습니다.",
    "Token returned by `getStaffCsrfToken` and bound to the browser session.":
        "getStaffCsrfToken이 반환하며 브라우저 세션에 귀속되는 토큰입니다.",
    "Defense-in-depth browser consistency guard for staffSession operations. When present it must be one canonical UUID equal to the already authenticated session principal. Missing remains compatible; malformed/duplicate values return the InvalidExpectedStaffActor response and a mismatch returns StaffSessionActorMismatch before controller execution, audit success, mutation, or session activity renewal. Login bootstrap and post-login identity verification intentionally omit it.":
        "staffSession 작업을 위한 심층 방어용 브라우저 일관성 guard입니다. 전달할 때는 이미 인증된 세션 주체와 같은 하나의 정규 UUID여야 합니다. 생략은 호환되지만 형식 오류나 중복은 InvalidExpectedStaffActor, 불일치는 Controller 실행·감사 성공·변경·세션 활동 갱신 전에 StaffSessionActorMismatch를 반환합니다. 로그인 시작과 로그인 직후 신원 확인에서는 의도적으로 생략합니다.",
    "Zero-based page number.": "0부터 시작하는 페이지 번호입니다.",
    "Bounded number of rows returned in one admin list response.": "관리자 목록 응답 한 페이지에서 반환할 수 있는 제한된 행 수입니다.",
    "External-system ETag; it must identify the same version as expectedVersion.": "외부 시스템용 ETag이며 expectedVersion과 같은 버전을 가리켜야 합니다.",
    "Auditor navigation/filter interaction. Cursor requests reuse the first-page value.": "감사자의 탐색·필터 상호작용 식별자이며 cursor 요청은 첫 페이지의 값을 재사용합니다.",
    "Zero-based page number returned.": "응답으로 반환된 0부터 시작하는 페이지 번호입니다.",
    "Maximum number of rows requested for this page.": "이 페이지에서 요청한 최대 행 수입니다.",
    "Total rows matching this list.": "목록 조건에 일치하는 전체 행 수입니다.",
    "Total pages at the requested page size.": "요청한 페이지 크기를 기준으로 계산한 전체 페이지 수입니다.",
    "The optional expected staff actor header is present but is not one canonical UUID value.": "선택적인 직원 actor 확인 헤더가 전달됐지만 하나의 정규 UUID 값이 아닙니다.",
    "The browser's confirmed actor differs from the authenticated session principal.": "브라우저가 확인한 actor와 인증된 세션 주체가 다릅니다.",
    "RFC 9457 request validation failure, including a present expected-actor header that is not one canonical UUID value.": "정규 UUID가 아닌 expected-actor 헤더를 포함한 RFC 9457 요청 검증 실패 응답입니다.",
    "RFC 9457 business conflict, including a confirmed browser actor that differs from the authenticated session principal.": "브라우저가 확인한 actor와 인증 세션 주체가 다른 경우를 포함한 RFC 9457 비즈니스 충돌 응답입니다.",
    "RFC 9457 Problem Details": "RFC 9457 Problem Details 오류 응답입니다.",
    "Anonymous requester name; ignored when a valid customer session supplies the requester identity.": "익명 문의자의 이름입니다. 유효한 고객 세션이 요청자 신원을 제공하면 이 값은 사용하지 않습니다.",
    "Anonymous requester email; ignored when a valid customer session supplies the requester identity.": "익명 문의자의 이메일입니다. 유효한 고객 세션이 요청자 신원을 제공하면 이 값은 사용하지 않습니다.",
    "Returned only at creation in both anonymous and authenticated modes for ticket-scoped lookup/claim recovery; server stores only a digest and revokes active tokens when ownership is explicitly claimed.": "익명·인증 모드 모두 생성 시에만 반환하는 티켓 범위 조회·연결 복구 토큰입니다. 서버는 digest만 저장하고 소유권이 명시적으로 연결되면 활성 토큰을 폐기합니다.",
    "Stable customer command identifier retained across transport retries.": "전송 재시도 사이에도 유지하는 안정적인 고객 명령 식별자입니다.",
    "Customer-visible ticket status projection.": "고객에게 공개할 수 있는 티켓 상태 projection입니다.",
    "Canonical status projection for staff ticket reads and commands.": "직원용 티켓 조회와 명령에서 사용하는 정규 상태 projection입니다.",
    "READ is always present for a successful staff detail; UPDATE is present only when the current actor may mutate the current ticket.": "직원 상세 조회가 성공하면 READ가 항상 포함되고, 현재 actor가 티켓을 변경할 수 있을 때만 UPDATE가 포함됩니다.",
    "Active groups and their active members, projected only on the audited staff detail surface.": "감사되는 직원 상세 화면에만 projection되는 활성 그룹과 활성 구성원입니다.",
    "Null for requesterless INTERNAL_WORK_ITEM tickets.": "요청자가 없는 INTERNAL_WORK_ITEM 티켓에서는 null입니다.",
    "Identifies one canonical update request for the authenticated staff actor. An exact retry returns the original result; reuse for a different payload, ticket, or staff ticket operation returns 409 without mutation.": "인증된 직원 actor의 정규 업데이트 요청 하나를 식별합니다. 동일 재시도에는 원래 결과를 반환하고, 다른 payload·티켓·작업에 재사용하면 변경 없이 409를 반환합니다.",
    "Explicit persisted high-risk audit grants; empty for non-auditors and new auditors.": "명시적으로 영속화한 고위험 감사 권한이며 비감사자와 새 감사자에게는 비어 있습니다.",
    "Immutable normalized registry identifier.": "변경할 수 없는 정규화된 registry 식별자입니다.",
    "Exact canonical public DNS hostnames; wildcards, localhost, and IP literals are forbidden.": "정확한 정규 public DNS 호스트명이며 wildcard, localhost, IP literal은 허용하지 않습니다.",
    "Present only while the system is active and the stored host remains allowed.": "시스템이 활성 상태이고 저장된 호스트가 계속 허용되는 동안에만 값이 존재합니다.",
    "Exact-host HTTPS URL; userinfo and credential-like query keys are forbidden.": "호스트가 정확히 일치하는 HTTPS URL이며 userinfo와 자격 증명 형태의 query key는 금지합니다.",
    "Optional literal IP or CIDR allowlist. Absence means no network restriction at this client seam.": "선택적인 literal IP 또는 CIDR 허용 목록입니다. 값이 없으면 이 클라이언트 경계에서는 네트워크 제한을 적용하지 않습니다.",
    "Latest effective credential expiry for list display.": "목록 표시에 사용하는 현재 유효 자격 증명의 가장 늦은 만료 시각입니다.",
    "Legacy field name for the input-independent `[PROTECTED]` routine marker. Exact query content is available only through the separately authorized, reason-gated reveal operation.\n": "입력값과 무관한 `[PROTECTED]` 일상 조회 표식에 남아 있는 레거시 필드명입니다. 정확한 검색어는 별도 권한과 사유가 필요한 공개 작업에서만 확인할 수 있습니다.\n",
    "True when only the first 100 linked opens are returned.": "연결된 열람 중 처음 100건만 반환했으면 true입니다.",
    "Current activated immutable version, independent of the returned version.": "반환된 버전과 별개인 현재 활성 불변 버전입니다.",
    "IANA timezone of the current activated immutable schedule version.": "현재 활성화된 불변 일정 버전의 IANA timezone입니다.",
    "Must be no more than 366 days after startAt.": "startAt 이후 최대 366일 이내여야 합니다.",
    "Current activated immutable policy version, independent of the returned version.": "반환된 버전과 별개인 현재 활성 불변 정책 버전입니다.",
    "Existing policy replaced by candidate during ordered preview evaluation.": "순서가 있는 미리보기 평가에서 후보 정책으로 대체할 기존 정책입니다.",
    "DESKSEED_CUSTOMER_SESSION; Path=/; HttpOnly; Secure; SameSite=Lax. Only a digest of the opaque value is retained server-side.": "DESKSEED_CUSTOMER_SESSION 쿠키입니다. Path=/, HttpOnly, Secure, SameSite=Lax를 사용하며 서버에는 opaque 값의 digest만 저장합니다.",
    "Expired DESKSEED_CUSTOMER_SESSION cookie": "만료 처리된 DESKSEED_CUSTOMER_SESSION 쿠키입니다.",
    "Opaque HttpOnly, Secure, SameSite=Lax cookie. The server stores only a digest and resolves it to exactly one active CustomerAccount.": "Opaque HttpOnly, Secure, SameSite=Lax 쿠키입니다. 서버는 digest만 저장하고 정확히 하나의 활성 CustomerAccount로 해석합니다.",
    "Token derived for and bound to the opaque customer server session.": "opaque 고객 서버 세션에서 파생되어 해당 세션에 귀속되는 토큰입니다.",
    "UTC epoch second when the current client window resets.": "현재 클라이언트의 요청 제한 구간이 초기화되는 UTC epoch 초입니다.",
    "Client rate limit exceeded": "클라이언트 요청 제한을 초과했습니다.",
    "Creates the first PUBLIC comment for CUSTOMER_REQUEST and the first INTERNAL comment for INTERNAL_WORK_ITEM.": "CUSTOMER_REQUEST에는 첫 PUBLIC 코멘트를, INTERNAL_WORK_ITEM에는 첫 INTERNAL 코멘트를 생성합니다.",
    "Quoted schedule aggregate version.": "따옴표로 감싼 일정 aggregate 버전입니다.",
    "Quoted policy aggregate version.": "따옴표로 감싼 정책 aggregate 버전입니다.",
}


def operation_description(operation: dict[str, Any], operation_id: str) -> str:
    tags = set(operation.get("tags") or [])
    if "Platform Tickets" in tags:
        actor = "INTEGRATION_CLIENT가 scoped API key와 resource constraint 검사를 통과한 뒤 사용합니다."
    elif "Customer" in " ".join(tags):
        actor = "고객용 projection만 반환하며 INTERNAL 댓글, 자식 관계, 직원 전용 필드와 감사 메타데이터를 노출하지 않습니다."
    elif "Audit" in tags:
        actor = "별도 감사 권한을 가진 직원만 사용할 수 있고 감사 화면 조회·공개·내보내기 행위도 다시 감사됩니다."
    elif "Admin" in " ".join(tags) or "Integration Admin" in tags:
        actor = "ADMIN 또는 명시된 관리 권한이 있는 직원만 사용할 수 있으며 변경은 보안 감사와 함께 처리됩니다."
    else:
        actor = "인증된 직원의 역할과 티켓 접근·변경 권한을 서버에서 확인한 뒤 처리합니다."
    existing = str(operation.get("description") or "").strip()
    purpose = OPERATION_SUMMARIES[operation_id]
    if existing.startswith(f"{purpose} API입니다."):
        return existing
    description = f"{purpose} API입니다. {actor}"
    if existing:
        description += f" 기존 계약의 세부 조건: {existing}"
    return description


def response_description(status: str, existing: str) -> str:
    if re.search("[가-힣]", existing):
        return existing
    if status.startswith("2"):
        prefix = "요청이 정상적으로 처리되었습니다."
    elif status == "400":
        prefix = "요청 형식이나 입력값이 유효하지 않습니다."
    elif status == "401":
        prefix = "인증되지 않은 요청입니다."
    elif status == "403":
        prefix = "인증된 주체에게 필요한 권한이 없습니다."
    elif status == "404":
        prefix = "조회할 수 없거나 노출하면 안 되는 자원입니다."
    elif status == "409":
        prefix = "동시성, 상태 또는 멱등성 충돌이 발생했습니다."
    elif status == "429":
        prefix = "요청 제한을 초과했으며 Retry-After 정책을 따라야 합니다."
    elif status.startswith("5"):
        prefix = "서버가 안전하게 요청을 완료할 수 없었습니다."
    else:
        prefix = "요청 처리 결과입니다."
    return f"{prefix} 세부 계약: {existing}" if existing else prefix


def parameter_description(parameter: dict[str, Any]) -> str:
    name = str(parameter.get("name") or "파라미터")
    location = {"path": "경로", "query": "조회 조건", "header": "HTTP 헤더", "cookie": "쿠키"}.get(
        parameter.get("in"),
        "요청",
    )
    existing = str(parameter.get("description") or "").strip()
    if re.search("[가-힣]", existing):
        return existing
    prefix = f"{location}로 전달하는 `{name}` 값입니다."
    return f"{prefix} 세부 계약: {existing}" if existing else prefix


def property_description(name: str, schema: dict[str, Any]) -> str:
    if name.lower().endswith("id"):
        return f"{name} 식별자입니다."
    if name == "ticketNumber":
        return "사람이 식별하고 검색할 때 사용하는 티켓 번호입니다."
    if name in {"subject", "message", "body", "reason", "detail", "displayName", "name"}:
        return f"{name}에 사용하는 입력 또는 표시 문자열입니다."
    if name.lower().endswith("at") or schema.get("format") == "date-time":
        return "UTC 기준 ISO 8601 시각입니다."
    if name in {"status", "priority", "visibility", "role", "source", "actorType"}:
        return f"{name}의 허용된 열거 값입니다."
    if name in {"version", "expectedVersion"}:
        return "낙관적 동시성 제어에 사용하는 버전입니다."
    if "cursor" in name.lower():
        return "서버가 발급한 불투명 페이지 커서입니다. 값을 해석하거나 변경하지 않습니다."
    if "token" in name.lower() or "secret" in name.lower() or "password" in name.lower():
        return "민감한 인증 값입니다. 예시는 사용할 수 없는 합성 값이며 로그나 감사 기록에 저장하면 안 됩니다."
    return f"{name} 값입니다."


def example_for(name: str, schema: dict[str, Any]) -> Any:
    key = name.lower()
    if "$ref" in schema:
        return None
    if schema.get("const") is not None:
        return schema["const"]
    if schema.get("enum"):
        return schema["enum"][0]
    if "password" in key:
        return "not-a-real-password"
    if "secret" in key or "apikey" in key or key == "key":
        return "example-secret-not-valid"
    if "token" in key:
        return "example-token-not-valid-0000000000000000"
    if schema.get("format") == "uuid" or key.endswith("id"):
        return "11111111-1111-4111-8111-111111111111"
    if schema.get("format") == "email" or key == "email":
        return "customer@example.com"
    if schema.get("format") == "date-time" or key.endswith("at"):
        return "2026-08-10T09:00:00Z"
    if schema.get("format") in {"uri", "uri-reference"} or "url" in key:
        return "https://orders.example.com/orders/ORD-2026-1042"
    if key == "ticketnumber":
        return 1042
    if key in {"subject", "title"}:
        return "결제가 중복으로 처리됐어요"
    if key in {"message", "body", "commentbody"}:
        return "주문 ORD-2026-1042의 결제가 두 번 승인되어 확인이 필요합니다."
    if key in {"name", "displayname", "authordisplayname"}:
        return "김고객"
    if "reason" in key:
        return "결제 기록 확인을 위한 감사 조사"
    if "cursor" in key:
        return "opaque-cursor-example"
    typ = schema.get("type")
    if isinstance(typ, list):
        typ = next((item for item in typ if item != "null"), None)
    if typ == "boolean":
        return True
    if typ == "integer":
        return max(int(schema.get("minimum", 0)), 1)
    if typ == "number":
        return float(max(schema.get("minimum", 0), 1))
    if typ == "string":
        return "예시 값"
    return None


def schema_example(
    schema: dict[str, Any],
    schemas: dict[str, Any],
    name: str = "value",
    stack: tuple[str, ...] = (),
) -> Any:
    reference = schema.get("$ref")
    if isinstance(reference, str) and reference.startswith("#/components/schemas/"):
        target = reference.rsplit("/", 1)[-1]
        if target in stack:
            return None
        return schema_example(schemas.get(target, {}), schemas, target, stack + (target,))
    direct = example_for(name, schema)
    if direct is not None:
        return direct
    if schema.get("type") == "array":
        item = schema_example(schema.get("items", {}), schemas, name, stack)
        return [] if item is None else [item]
    properties = schema.get("properties")
    if isinstance(properties, dict):
        result = {}
        for property_name, prop in properties.items():
            if not isinstance(prop, dict):
                continue
            value = schema_example(prop, schemas, property_name, stack)
            if value is not None:
                result[property_name] = value
        return result or None
    for variant_key in ("oneOf", "anyOf", "allOf"):
        variants = schema.get(variant_key)
        if isinstance(variants, list) and variants:
            return schema_example(variants[0], schemas, name, stack)
    return None


def translate_descriptions(value: Any, location: str = "$") -> None:
    if isinstance(value, dict):
        description = value.get("description")
        if isinstance(description, str) and not re.search("[가-힣]", description):
            translated = DESCRIPTION_TRANSLATIONS.get(description)
            if translated is None:
                raise ValueError(f"Korean description is missing at {location}: {description}")
            value["description"] = translated
        for key, child in value.items():
            translate_descriptions(child, f"{location}/{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            translate_descriptions(child, f"{location}/{index}")


def enrich(path: Path) -> None:
    document = yaml.safe_load(path.read_text(encoding="utf-8"))
    document.setdefault("info", {})["description"] = (
        "Deskseed의 커밋된 OpenAPI 3.1 계약입니다. API 목적과 데이터 경계는 한국어로 설명하며 "
        "예시는 모두 합성 데이터입니다. 실제 비밀번호, 세션, API key 또는 고객 정보를 문서에 입력하거나 저장하지 마세요."
    )
    known_tags = {tag.get("name") for tag in document.get("tags", []) if isinstance(tag, dict)}
    for tag_name in sorted({tag for item in document.get("paths", {}).values() for op in item.values() if isinstance(op, dict) for tag in op.get("tags", [])}):
        if tag_name in known_tags:
            for tag in document["tags"]:
                if tag.get("name") == tag_name:
                    tag["description"] = TAG_DESCRIPTIONS.get(tag_name, f"{tag_name} API 묶음입니다.")
        else:
            document.setdefault("tags", []).append({"name": tag_name, "description": TAG_DESCRIPTIONS.get(tag_name, f"{tag_name} API 묶음입니다.")})

    for path_item in document.get("paths", {}).values():
        if not isinstance(path_item, dict):
            continue
        for method, operation in path_item.items():
            if method not in {"get", "post", "put", "patch", "delete", "head", "options", "trace"} or not isinstance(operation, dict):
                continue
            operation_id = operation.get("operationId")
            if operation_id not in OPERATION_SUMMARIES:
                raise ValueError(f"Korean summary is missing for operationId={operation_id}")
            operation["summary"] = OPERATION_SUMMARIES[operation_id]
            operation["description"] = operation_description(operation, operation_id)
            for parameter in [*path_item.get("parameters", []), *operation.get("parameters", [])]:
                if isinstance(parameter, dict) and "$ref" not in parameter:
                    parameter["description"] = parameter_description(parameter)
            for status, response in operation.get("responses", {}).items():
                if isinstance(response, dict) and "$ref" not in response:
                    response["description"] = response_description(str(status), str(response.get("description") or ""))

    schemas = document.get("components", {}).get("schemas", {})
    for schema_name, schema in schemas.items():
        if not isinstance(schema, dict):
            continue
        schema.setdefault("description", f"{schema_name} 요청 또는 응답 모델입니다.")
        for name, prop in schema.get("properties", {}).items():
            if not isinstance(prop, dict):
                continue
            prop.setdefault("description", property_description(name, prop))
            example = example_for(name, prop)
            if example is not None:
                prop.setdefault("example", example)
        example = schema_example(schema, schemas, schema_name, (schema_name,))
        if example is not None:
            schema.setdefault("example", example)

    translate_descriptions(document)

    rendered = yaml.safe_dump(
        document,
        allow_unicode=True,
        sort_keys=False,
        width=120,
        default_flow_style=False,
    )
    path.write_text(rendered, encoding="utf-8")


def main() -> None:
    for contract in CONTRACTS:
        enrich(contract)
        print(contract.relative_to(ROOT))


if __name__ == "__main__":
    main()
