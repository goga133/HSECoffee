package com.goga133.hsecoffee.objects

import java.util.*
import javax.persistence.*

@Entity
data class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    val userid: Long = 0,
    val email: String? = null,
    @Column(name = "first_name")
    var firstName: String? = null,
    @Column(name = "last_name")
    var lastName: String? = null,
    @Column(name = "sex")
    @Enumerated(EnumType.STRING)
    var sex: Sex? = null,
    @Column(name = "created_date")
    @Temporal(TemporalType.TIMESTAMP)
    val createdDate: Date = Date(),
    @Column(name = "faculty")
    var faculty: String?,

    @Column(name = "contacts")
    @ElementCollection
    var contacts: List<String>
) {

    constructor(email: String?) : this(
        email = email,
        firstName = null,
        lastName = null,
        faculty = null,
        contacts = ArrayList()
    ) {

    }

    constructor() : this(null) {

    }

    fun setParams(user : User){
        firstName = user.firstName
        lastName = user.lastName
        sex = user.sex
        faculty = user.faculty
        contacts = user.contacts
    }
}