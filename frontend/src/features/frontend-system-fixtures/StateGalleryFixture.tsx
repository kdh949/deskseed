import { Notification, ScreenState } from '../../shared/ui/system'

export function StateGalleryFixture() {
  return (
    <main className="fixture-state-gallery" id="fixture-state-main">
      <h1>Deskseed 상태 프리미티브</h1>
      <div className="fixture-notification-stack">
        <Notification tone="success" title="변경사항을 저장했습니다." />
        <Notification tone="warning" title="열린 child ticket이 있습니다." />
        <Notification tone="danger" title="요청을 완료하지 못했습니다.">
          <p>요청 ID safe-fixture-id</p>
        </Notification>
        <Notification
          tone="conflict"
          title="다른 상담사가 담당자를 변경했습니다."
        />
      </div>
      <div className="fixture-state-grid">
        <ScreenState kind="loading" compact title="티켓을 불러오는 중입니다." />
        <ScreenState kind="empty" compact title="표시할 티켓이 없습니다." />
        <ScreenState
          kind="error"
          compact
          title="목록을 불러오지 못했습니다."
          requestId="safe-fixture-id"
        />
        <ScreenState
          kind="denied"
          compact
          title="이 화면을 볼 권한이 없습니다."
        />
        <ScreenState
          kind="conflict"
          compact
          title="서버의 최신 값과 충돌했습니다."
        />
        <ScreenState
          kind="stale"
          compact
          title="최신 정보를 다시 확인해 주세요."
        />
      </div>
    </main>
  )
}
