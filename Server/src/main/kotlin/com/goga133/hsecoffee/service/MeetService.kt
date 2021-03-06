package com.goga133.hsecoffee.service

import com.goga133.hsecoffee.data.status.CancelStatus
import com.goga133.hsecoffee.entity.Meet
import com.goga133.hsecoffee.entity.Search
import com.goga133.hsecoffee.entity.SearchParams
import com.goga133.hsecoffee.entity.User
import com.goga133.hsecoffee.data.status.MeetStatus
import com.goga133.hsecoffee.repository.MeetRepository
import com.goga133.hsecoffee.repository.SearchRepository
import com.goga133.hsecoffee.repository.UserRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.util.*
import javax.persistence.Transient

/**
 * Сервис для работы с встречами.
 */
@Service
class MeetService {
    /**
     * Логгер.
     */
    private val logger: Logger = LoggerFactory.getLogger(MeetService::class.java)

    /**
     * Репозиторий встреч.
     */
    @Qualifier("meetRepository")
    @Autowired
    private val meetRepository: MeetRepository? = null

    /**
     * Репозиторий пользователей.
     */
    @Qualifier("userRepository")
    @Autowired
    private val userRepository: UserRepository? = null

    /**
     * Репозиторий поиска.
     */
    @Qualifier("searchRepository")
    @Autowired
    private val searchRepository: SearchRepository? = null

    /**
     * Метод для получения текущей встречи пользователя.
     * Если у пользователя нет встречи, то вернётся пустая встреча с [MeetStatus.NONE]
     * @param user - пользователь, относительно которого произоводится поиск текущей встречи.
     */
    fun getMeet(user: User): Meet {
        // Пользователь может искать, может быть уже в встрече, либо ничего.
        // Если он ищет - значит в серчрепе что-то найдётся.
        // Если он не ищет, значит либо встречается, либо ничего.
        // Тогда проверим на статус последней встречи, если встречи нет или статус финешед - значит он свободен
        // иначе - он встречается.

        if (userRepository == null || !userRepository.existsUserById(user.id)) {
            logger.warn("Пользователь $user не найден в базе данных.")
            return Meet()
        }

        // Мы ищем среди всех встреч
        val meet = meetRepository?.findAllByUser1OrUser2(user, user)?.maxByOrNull { it.expiresDate }

        if (searchRepository?.findSearchByFinder(user) != null) {
            return Meet(user, MeetStatus.SEARCH)
        } else if (meet != null) {
            updateMeetStatus(meet)

            if (meet.meetStatus == MeetStatus.FINISHED) {
                return Meet()
            }

            return meet
        }

        return Meet()
    }

    /**
     * Метод, который дыдаёт коллекцию из всех законченных встреч пользователя.
     * Встреча считается законченной, если у неё [MeetStatus] = [MeetStatus.FINISHED]
     *
     * @param user - пользователь, относительно которого произоводится поиск законченных встреч.
     * @return коллекция встреч
     * @see Meet
     */
    fun getMeets(user: User): Collection<Meet> {
        if (userRepository == null || !userRepository.existsUserById(user.id)) {
            return arrayListOf()
        }

        return meetRepository?.findAll()?.filter { it ->
            (it.user1 == user || it.user2 == user) && it.apply { updateMeetStatus(it) }.meetStatus == MeetStatus.FINISHED
        } ?: listOf()
    }

    /**
     * Метод для отмены пользователем встречи.
     * @param user - пользователь, относительно которого производится отмена встречи.
     * @return [CancelStatus.SUCCESS] - если отмена успешна, [CancelStatus.FAIL] - если отмена неудачна.
     * @see CancelStatus
     */
    @Transient
    fun cancelSearch(user: User): CancelStatus {
        if (userRepository == null || !userRepository.existsUserById(user.id)) {
            logger.warn("Пользователь $user не найден в базе данных.")
            return CancelStatus.FAIL
        }

        val finderSearch = searchRepository?.findSearchByFinder(user)

        if (finderSearch != null) {
            searchRepository?.delete(finderSearch)

            logger.debug("Пользователь $user отменил встречу.")
            return CancelStatus.SUCCESS
        }

        return CancelStatus.NOT_ALLOWED
    }

    /**
     * Метод для поиска встречи.
     *
     *
     * @param user - пользователь, который производит поиск встречи.
     * @param searchParams - параметры поиска, с которыми производится поиск встречи пользователем.
     *
     * @return [MeetStatus.ERROR] - если во время посиска произошла ошибка,
     * [MeetStatus.ACTIVE] - если встреча уже идёт или она была найдена.
     * [MeetStatus.SEARCH] - если встреча не была найдена и ведётся поиск встречи.
     *
     * @see MeetStatus
     * @see SearchParams
     */
    @Transient
    fun searchMeet(user: User, searchParams: SearchParams): MeetStatus {
        if (userRepository == null || !userRepository.existsUserById(user.id) || meetRepository == null || searchRepository == null) {
            return MeetStatus.ERROR
        }

        val meet = meetRepository.findAllByUser1OrUser2(user, user)?.maxByOrNull { it.expiresDate }.apply {
            this?.let {
                updateMeetStatus(it)
            }
        }

        if (meet?.meetStatus == MeetStatus.ACTIVE)
            return MeetStatus.ACTIVE

        // Если его нет в доске поиска:
        if (searchRepository.findSearchByFinder(user) == null) {
            val searches = searchRepository.findAll()

            val finder = searches.firstOrNull {
                CheckerSearch(user, searchParams, it.finder, it.searchParams).check()
            }

            // Если поиск неудачен, то добавляем в зал ожидания.
            return if (finder?.finder == null) {
                searchRepository.save(Search(user, searchParams))

                MeetStatus.SEARCH
            }
            // Если поиск удачен - делаем встречу.
            else {
                searchRepository.delete(finder)

                meetRepository.save(Meet(user, finder.finder, MeetStatus.ACTIVE))

                MeetStatus.ACTIVE
            }
        }
        return MeetStatus.SEARCH
    }

    /**
     * Метод для проверки законченности встречи. Если время действия встречи [Meet.expiresDate] вышло,
     * то она перейдёт в статус [MeetStatus.FINISHED]
     *
     * @see Meet
     */
    private fun updateMeetStatus(meet: Meet) {
        if (meetRepository == null || !meetRepository.existsMeetById(meet.id)) {
            return
        }

        if (meet.expiresDate.before(Date()) || meet.meetStatus == MeetStatus.FINISHED) {
            meet.meetStatus = MeetStatus.FINISHED

            logger.debug("Встреча $meet закончена.")
        }

        meetRepository.save(meet)
    }

    /**
     * Класс для проверки поисковых интересов двух пользователей.
     * @param user1 - первый пользователь.
     * @param params1 - поисковые параметры первого пользователя.
     * @param user2 - второй пользователь.
     * @param params2 - поисковые параметры второго пользователя.
     */
    private inner class CheckerSearch(
        val user1: User,
        val params1: SearchParams,
        val user2: User,
        val params2: SearchParams
    ) {
        /**
         * Метод для проверки годности поисковых запросов двух пользователей.
         */
        fun check(): Boolean {
            return checkFaculties() && checkGenders() && checkDegrees() && checkCourses()
        }

        /**
         * Метод для проверки факультетов
         */
        private fun checkFaculties(): Boolean {
            return params1.faculties.contains(user2.faculty) &&
                    params2.faculties.contains(user1.faculty)
        }

        /**
         * Метод для проверки полов.
         */
        private fun checkGenders(): Boolean {
            return params1.genders.contains(user2.gender) &&
                    params2.genders.contains(user1.gender)
        }

        /**
         * Метод для проверки степени обучения.
         */
        private fun checkDegrees(): Boolean {
            return params1.degrees.contains(user2.degree) &&
                    params2.degrees.contains(user1.degree)
        }

        /**
         * Метод для проверки курсов.
         */
        private fun checkCourses(): Boolean {
            return params1.minCourse <= user2.course &&
                    params1.maxCourse >= user2.course &&
                    params2.minCourse <= user1.course &&
                    params2.maxCourse >= user1.course
        }
    }
}