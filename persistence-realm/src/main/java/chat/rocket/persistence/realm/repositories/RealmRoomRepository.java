package chat.rocket.persistence.realm.repositories;

import android.os.Looper;
import android.support.v4.util.Pair;
import com.fernandocejas.arrow.optional.Optional;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.realm.Realm;
import io.realm.RealmResults;

import java.util.ArrayList;
import java.util.List;
import chat.rocket.core.models.Room;
import chat.rocket.core.models.RoomHistoryState;
import chat.rocket.core.repositories.RoomRepository;
import chat.rocket.persistence.realm.RealmStore;
import chat.rocket.persistence.realm.models.ddp.RealmRoom;
import chat.rocket.persistence.realm.models.internal.LoadMessageProcedure;
import hu.akarnokd.rxjava.interop.RxJavaInterop;

public class RealmRoomRepository extends RealmRepository implements RoomRepository {

  private final String hostname;

  public RealmRoomRepository(String hostname) {
    this.hostname = hostname;
  }

  @Override
  public Flowable<List<Room>> getAll() {
    return Flowable.defer(() -> Flowable.using(
        () -> new Pair<>(RealmStore.getRealm(hostname), Looper.myLooper()),
        pair -> RxJavaInterop.toV2Flowable(
            pair.first.where(RealmRoom.class)
                .findAll()
                .asObservable()),
        pair -> close(pair.first, pair.second)
    )
        .unsubscribeOn(AndroidSchedulers.from(Looper.myLooper()))
        .filter(roomSubscriptions -> roomSubscriptions != null && roomSubscriptions.isLoaded()
            && roomSubscriptions.isValid())
        .map(this::toList));
  }

  @Override
  public Flowable<Optional<Room>> getById(String roomId) {
    return Flowable.defer(() -> Flowable.using(
        () -> new Pair<>(RealmStore.getRealm(hostname), Looper.myLooper()),
        pair -> {
          RealmRoom realmRoom = pair.first.where(RealmRoom.class)
              .equalTo(RealmRoom.ROOM_ID, roomId)
              .findFirst();

          if (realmRoom == null) {
            return Flowable.just(Optional.<RealmRoom>absent());
          }

          return RxJavaInterop.toV2Flowable(
              realmRoom
                  .<RealmRoom>asObservable()
                  .filter(
                      roomSubscription -> roomSubscription.isLoaded()
                          && roomSubscription.isValid())
                  .map(Optional::of));
        },
        pair -> close(pair.first, pair.second)
    )
        .unsubscribeOn(AndroidSchedulers.from(Looper.myLooper()))
        .map(optional -> {
          if (optional.isPresent()) {
            return Optional.of(optional.get().asRoom());
          }

          return Optional.absent();
        }));
  }

  @Override
  public Flowable<Optional<RoomHistoryState>> getHistoryStateByRoomId(String roomId) {
    return Flowable.defer(() -> Flowable.using(
        () -> new Pair<>(RealmStore.getRealm(hostname), Looper.myLooper()),
        pair -> {

          LoadMessageProcedure messageProcedure = pair.first.where(LoadMessageProcedure.class)
              .equalTo(LoadMessageProcedure.ID, roomId)
              .findFirst();

          if (messageProcedure == null) {
            return Flowable.just(Optional.<LoadMessageProcedure>absent());
          }

          return RxJavaInterop.toV2Flowable(
              messageProcedure
                  .<LoadMessageProcedure>asObservable()
                  .filter(loadMessageProcedure -> loadMessageProcedure.isLoaded()
                      && loadMessageProcedure.isValid())
                  .map(Optional::of));
        },
        pair -> close(pair.first, pair.second)
    )
        .unsubscribeOn(AndroidSchedulers.from(Looper.myLooper()))
        .map(optional -> {
          if (optional.isPresent()) {
            return Optional.of(optional.get().asRoomHistoryState());
          }

          return Optional.absent();
        }));
  }

  @Override
  public Single<Boolean> setHistoryState(RoomHistoryState roomHistoryState) {
    return Single.defer(() -> {
      final Realm realm = RealmStore.getRealm(hostname);
      final Looper looper = Looper.myLooper();

      if (realm == null || looper == null) {
        return Single.just(false);
      }

      LoadMessageProcedure loadMessage = new LoadMessageProcedure();
      loadMessage.setRoomId(roomHistoryState.getRoomId());
      loadMessage.setSyncState(roomHistoryState.getSyncState());
      loadMessage.setCount(roomHistoryState.getCount());
      loadMessage.setReset(roomHistoryState.isReset());
      loadMessage.setHasNext(!roomHistoryState.isComplete());
      loadMessage.setTimestamp(roomHistoryState.getTimestamp());

      realm.beginTransaction();

      return RxJavaInterop.toV2Flowable(realm.copyToRealmOrUpdate(loadMessage)
          .asObservable())
          .filter(realmObject -> realmObject.isLoaded() && realmObject.isValid())
          .firstElement()
          .doOnSuccess(it -> realm.commitTransaction())
          .doOnError(throwable -> realm.cancelTransaction())
          .doOnEvent((realmObject, throwable) -> close(realm, looper))
          .toSingle()
          .map(realmObject -> true);
    });
  }

  private List<Room> toList(RealmResults<RealmRoom> realmRooms) {
    int total = realmRooms.size();

    final List<Room> roomList = new ArrayList<>(total);

    for (int i = 0; i < total; i++) {
      roomList.add(realmRooms.get(i).asRoom());
    }

    return roomList;
  }
}
